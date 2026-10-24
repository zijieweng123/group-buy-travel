import groovy.json.JsonOutput
import java.util.concurrent.atomic.AtomicLong

String label = sampler.getName()
boolean leader = label.endsWith('leader')
boolean create = label.startsWith('order-')
if (label == 'order-leader') {
    vars.put('flow.count', String.valueOf((vars.get('flow.count') ?: '0').toInteger() + 1))
    vars.put('flow.ok', 'true')
    vars.remove('teamId')
    vars.remove('orderId')
    AtomicLong sequence = (AtomicLong) props.get('perf.order.sequence')
    long number = sequence.getAndAdd(2)
    vars.put('leaderNo', String.format('%012d', number))
    vars.put('memberNo', String.format('%012d', number + 1))
    vars.put('flowId', props.getProperty('run.id') + '-' + number)
}
String externalNo = vars.get(leader ? 'leaderNo' : 'memberNo')
String user = 'lt-' + props.getProperty('run.id') + '-' + externalNo
vars.put('userId', user)
vars.put('outTradeNo', externalNo)
vars.put('businessCode', '')
Map body
if (create) {
    body = [userId:user, activityId:Long.parseLong(props.getProperty('activity.id')),
            goodsId:'9890001', source:'s01', channel:'c01', outTradeNo:externalNo,
            travelDate:props.getProperty('travel.date'), visitorName:'Load Test',
            visitorIdType:'PASSPORT', visitorIdNo:'P' + externalNo,
            notifyConfigVO:[notifyType:'MQ']]
    if (!leader) body.teamId = vars.get('teamId')
} else {
    body = [userId:user, source:'s01', channel:'c01', outTradeNo:externalNo,
            outTradeTime:System.currentTimeMillis()]
}
vars.put('request.body', JsonOutput.toJson(body))
