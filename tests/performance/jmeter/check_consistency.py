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


def run_checks(directory, activity):
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

    report={'passed':all(result['passed'] for result in results),'cases':results,
            'scope':'real orders, Redis inventory and Kafka consumed markers; no external payment or ticket-issuing provider'}
    (directory/'correctness.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    return report
