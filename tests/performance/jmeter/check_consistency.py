"""Small real-storage correctness cases, run before a throughput measurement."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
import json
from run_real_trade import (TRAVEL_DATE, fixture, order_body, http, settle, expect_success,
                            sql, rows, redis, verify)


def parallel(bodies, request):
    with ThreadPoolExecutor(max_workers=20) as pool:
        return list(pool.map(request, bodies))


def create(body):
    return http('/api/v1/group-buy/orders', body)


def occupancy(activity, travel_date=TRAVEL_DATE):
    return int(redis('GET', f'travel:calendar:stock:{activity}:9890001:{travel_date}') or 0)


def run_checks(directory, activity, include_notify_recovery=False, include_trade_reads=False):
    results=[]
    number=800000000000+(activity % 10000000)*1000

    # Identical outTradeNo while the first lock order is in flight.
    fixture(activity,20)
    body=order_body(activity,number)
    replies=parallel([body]*20,create)
    codes=Counter(reply.get('code') for reply in replies)
    persisted=int(sql(f'SELECT COUNT(*) FROM group_buy_order_list WHERE activity_id={activity}'))
    count=occupancy(activity)
    results.append({'case':'duplicate-create','requests':20,'codes':dict(codes),
                    'orders':persisted,'occupied':count,
                    'passed':persisted==count==1 and codes.get('0000',0)>=1 and
                    set(codes)<= {'0000','E0109'}})

    # A single paid order must never count twice toward a two-person team.
    activity+=1
    fixture(activity,20)
    body=order_body(activity,number+100)
    expect_success(create(body))
    replies=parallel([body]*20,settle)
    codes=Counter(reply.get('code') for reply in replies)
    team=list(map(int,rows(f'SELECT lock_count,complete_count,status FROM group_buy_order WHERE activity_id={activity}')[0]))
    tasks=int(sql(f'SELECT COUNT(*) FROM notify_task WHERE activity_id={activity}'))
    results.append({'case':'duplicate-settlement','requests':20,'codes':dict(codes),
                    'teamLockCount':team[0],'teamCompleteCount':team[1],'teamStatus':team[2],
                    'notifyTasks':tasks,'passed':team==[1,1,0] and tasks==0 and
                    codes.get('0000',0)>=1 and set(codes)<={'0000','E0104','0004'}})

    # Five tickets, fifty unique customers. Stock exhaustion is an expected rejection.
    activity+=1
    fixture(activity,5)
    replies=parallel([order_body(activity,number+200+i) for i in range(50)],create)
    codes=Counter(reply.get('code') for reply in replies)
    persisted=int(sql(f'SELECT COUNT(*) FROM group_buy_order_list WHERE activity_id={activity}'))
    count=occupancy(activity)
    results.append({'case':'calendar-last-five','requests':50,'capacity':5,'codes':dict(codes),
                    'orders':persisted,'occupied':count,'passed':persisted==count==5 and
                    codes.get('0000',0)==5 and set(codes)<={'0000','E0108'}})

    # Same activity/product, independently exhausted dates.
    activity+=1
    fixture(activity,3)
    other_date=(date.fromisoformat(TRAVEL_DATE)+timedelta(days=1)).isoformat()
    sql(f"INSERT INTO ticket_calendar_inventory(activity_id,goods_id,travel_date,available_stock,status) VALUES({activity},'9890001','{other_date}',4,1)")
    bodies=[order_body(activity,number+300+i,date=TRAVEL_DATE if i%2==0 else other_date) for i in range(50)]
    replies=parallel(bodies,create)
    codes=Counter(reply.get('code') for reply in replies)
    persisted={row[0]:int(row[1]) for row in rows(f'SELECT travel_date,COUNT(*) FROM group_buy_order_list WHERE activity_id={activity} GROUP BY travel_date')}
    occupied={TRAVEL_DATE:occupancy(activity),other_date:occupancy(activity,other_date)}
    expected={TRAVEL_DATE:3,other_date:4}
    results.append({'case':'calendar-date-isolation','requests':50,'codes':dict(codes),
                    'ordersByDate':persisted,'occupiedByDate':occupied,
                    'passed':persisted==occupied==expected and codes.get('0000',0)==7 and
                    set(codes)<={'0000','E0108'}})

    # One remaining team seat must produce exactly one additional order.
    activity+=1
    fixture(activity,100)
    leader=order_body(activity,number+400)
    expect_success(create(leader))
    team=expect_success(settle(leader))['teamId']
    bodies=[order_body(activity,number+401+i,team=team) for i in range(50)]
    replies=parallel(bodies,create)
    codes=Counter(reply.get('code') for reply in replies)
    winners=[body for body,reply in zip(bodies,replies) if reply.get('code')=='0000']
    if len(winners)==1:
        expect_success(settle(winners[0]))
    check=verify(activity,2)
    results.append({'case':'team-last-seat','requests':50,'codes':dict(codes),
                    'verification':check,'passed':len(winners)==1 and check['passed'] and
                    set(codes)<={'0000','E0005','E0006','E0008'}})

    # Two distinct unpaid members may settle at the same time. Completion must
    # depend on the locked database row rather than both pre-read snapshots.
    activity+=1
    fixture(activity,100)
    pending=[]
    for index in range(10):
        leader=order_body(activity,number+600+index*2)
        expect_success(create(leader))
        team=rows(f"SELECT team_id FROM group_buy_order_list WHERE user_id='{leader['userId']}' AND out_trade_no='{leader['outTradeNo']}'")[0][0]
        member=order_body(activity,number+601+index*2,team=team)
        expect_success(create(member))
        pending.extend((leader,member))
    replies=parallel(pending,settle)
    codes=Counter(reply.get('code') for reply in replies)
    check=verify(activity,20)
    results.append({'case':'simultaneous-distinct-settlements','requests':20,
                    'codes':dict(codes),'verification':check,
                    'passed':codes.get('0000',0)==20 and check['passed']})

    if include_notify_recovery:
        results.extend(notification_checks(activity))

    if include_trade_reads:
        results.extend(trade_read_checks(activity+20,number+10000))
        results.extend(atomic_reservation_checks(activity+40))

    report={'passed':all(result['passed'] for result in results),'cases':results,
            'scope':'real orders, Redis inventory and Kafka consumed markers; no external payment or ticket-issuing provider'}
    (directory/'correctness.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    return report


def notification_checks(activity):
    """Replay actual mapper SQL and recover a delivery whose ACK was not recorded."""
    import time
    import xml.etree.ElementTree as ET
    import run_real_trade as rt
    team,previous_count=rows(f'SELECT team_id,notify_count FROM notify_task WHERE activity_id={activity} AND notify_status=1 LIMIT 1')[0]
    assert team.isdigit() and len(team)==8
    root=ET.parse(rt.ROOT/'cultural-tourism-group-buy-app/src/main/resources/mybatis/mapper/notify_task_mapper.xml').getroot()
    statements=[]
    for name in ('updateNotifyTaskStatusSuccess','updateNotifyTaskStatusSuccessBatch','updateNotifyTaskStatusRetry','updateNotifyTaskStatusError'):
        node=root.find("update[@id='"+name+"']")
        assert node is not None
        text=node.text or ''
        for child in node:
            assert child.tag=='foreach'
            text+=child.attrib.get('open','')+(child.text or '')+child.attrib.get('close','')+(child.tail or '')
        text=text.replace('#{teamId}',"'"+team+"'")
        assert '#{' not in text
        statements.append(text+'; SELECT ROW_COUNT();')
    terminal=rows('START TRANSACTION;'+''.join(statements)+
                  f"SELECT notify_status,notify_count FROM notify_task WHERE team_id='{team}'; ROLLBACK;")
    protected=terminal==[['0'],['0'],['0'],['0'],['1',previous_count]]
    # Simulate process loss after broker ACK, before a status receipt reaches MySQL.
    sql(f"UPDATE notify_task SET notify_status=0,notify_count=0 WHERE team_id='{team}'")
    started=time.monotonic()
    restored=[]
    while time.monotonic()-started<30:
        restored=rows(f"SELECT notify_status,notify_count FROM notify_task WHERE team_id='{team}'")[0]
        if restored[0]=='1': break
        time.sleep(.5)
    check=verify(activity,20)
    return [{'case':'notification-success-is-terminal','sqlUpdateRowCounts':[row[0] for row in terminal[:4]],
             'passed':protected},
            {'case':'notification-recovery-after-unrecorded-ack','recoverySeconds':round(time.monotonic()-started,3),
             'status':restored,'verification':check,'passed':restored==['1','1'] and check['passed']}]


def trade_read_checks(activity, number):
    """Fresh SQL consolidation must preserve limits, date availability and settlement time checks."""
    results=[]
    fixture(activity,20)
    sql(f'UPDATE group_buy_activity SET take_limit_count=1 WHERE activity_id={activity}')
    leader=order_body(activity,number);expect_success(create(leader))
    team=expect_success(settle(leader))['teamId']
    repeated=order_body(activity,number+1);repeated['userId']=leader['userId']
    limited=create(repeated)
    held=occupancy(activity)
    member=order_body(activity,number+2,team=team)
    expect_success(create(member));expect_success(settle(member));check=verify(activity,2)
    results.append({'case':'fresh-read-user-participation-limit','rejectionCode':limited.get('code'),
                    'verification':check,'passed':limited.get('code')=='E0103' and held==1 and check['passed']})

    activity+=1;fixture(activity,20)
    sql(f'UPDATE ticket_calendar_inventory SET status=0 WHERE activity_id={activity}')
    reply=create(order_body(activity,number+100))
    count=int(sql(f'SELECT COUNT(*) FROM group_buy_order_list WHERE activity_id={activity}'))
    results.append({'case':'fresh-read-disabled-date-inventory','code':reply.get('code'),
                    'passed':reply.get('code')=='E0108' and count==occupancy(activity)==0})

    activity+=1;fixture(activity,20)
    expect_success(create(order_body(activity,number+200)))  # Populate marketing cache while active.
    sql(f'UPDATE group_buy_activity SET status=2 WHERE activity_id={activity}')
    inactive=create(order_body(activity,number+201))
    sql(f'UPDATE group_buy_activity SET status=1,end_time=NOW()-INTERVAL 1 MINUTE WHERE activity_id={activity}')
    expired=create(order_body(activity,number+202))
    missing=create(order_body(activity+10000,number+203))
    count=int(sql(f'SELECT COUNT(*) FROM group_buy_order_list WHERE activity_id={activity}'))
    codes=[inactive.get('code'),expired.get('code'),missing.get('code')]
    results.append({'case':'fresh-read-activity-status-time-and-missing-id','codes':codes,
                    'passed':codes==['E0101','E0102','E0101'] and count==occupancy(activity)==1})

    activity+=1;fixture(activity,20)
    body=order_body(activity,number+300);expect_success(create(body))
    sql(f'UPDATE group_buy_order SET valid_end_time=NOW()-INTERVAL 1 MINUTE WHERE activity_id={activity}')
    reply=settle(body)
    paid=int(sql(f'SELECT COUNT(*) FROM group_buy_order_list WHERE activity_id={activity} AND status=1'))
    completed=int(sql(f'SELECT SUM(complete_count) FROM group_buy_order WHERE activity_id={activity}'))
    tasks=int(sql(f'SELECT COUNT(*) FROM notify_task WHERE activity_id={activity}'))
    results.append({'case':'fresh-read-expired-team-settlement','code':reply.get('code'),
                    'passed':reply.get('code')=='E0106' and paid==completed==tasks==0 and occupancy(activity)==1})
    return results


def atomic_reservation_checks(activity):
    """Execute the exact packaged Lua source, including replay and pre-write error behavior."""
    import run_real_trade as rt
    script=(rt.ROOT/'cultural-tourism-group-buy-infrastructure/src/main/resources/redis/reserve-request-calendar.lua').read_text(encoding='utf-8')
    prefix='loadtest:reservation:'+str(activity)+':'
    request,second,third,stock,bad,bad_request=[prefix+name for name in ('first','second','third','stock','bad','bad-request')]
    keys=(request,second,third,stock,bad,bad_request)
    assert all(key.startswith('loadtest:reservation:') for key in keys)
    try:
        values=[redis('EVAL',script,2,request,stock,2,60000,'first-token'),
                redis('EVAL',script,2,request,stock,2,60000,'first-token'),
                redis('EVAL',script,2,request,stock,2,60000,'different-token')]
        held=int(redis('GET',stock) or 0)
        ttl=int(redis('PTTL',request))
        values.extend((redis('EVAL',script,2,second,stock,2,60000,'second-token'),
                       redis('EVAL',script,2,third,stock,2,60000,'third-token')))
        complete=int(redis('GET',stock) or 0)
        rejected=int(redis('EXISTS',third))
        replay={'case':'atomic-reservation-replay-and-capacity','results':values,
                'passed':list(map(str,values))==['1','1','0','1','-1'] and held==1 and complete==2 and rejected==0 and 0<ttl<=60000}
        redis('SET',bad,'invalid-integer')
        failed=False
        try: redis('EVAL',script,2,bad_request,bad,2,60000,'bad-token')
        except RuntimeError: failed=True
        error={'case':'atomic-reservation-invalid-counter-no-partial-write',
               'passed':failed and int(redis('EXISTS',bad_request))==0 and redis('GET',bad)=='invalid-integer'}
        return [replay,error]
    finally: redis('DEL',*keys)
