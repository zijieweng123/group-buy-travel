import groovy.json.JsonSlurper
try {
    if (prev.getResponseCode() != '200') throw new IllegalStateException('HTTP ' + prev.getResponseCode())
    def body = new JsonSlurper().parseText(prev.getResponseDataAsString())
    String code = String.valueOf(body.code)
    vars.put('businessCode', code)
    if (code != '0000') throw new IllegalStateException('Business code ' + code + ': ' + body.info)
    String label = prev.getSampleLabel()
    if (label.startsWith('order-')) {
        if (!(body.data?.orderId ==~ /[0-9]{12}/)) throw new IllegalStateException('Missing persisted order id')
        if (body.data.tradeOrderStatus != 0) throw new IllegalStateException('Expected newly created order')
        vars.put('orderId', String.valueOf(body.data.orderId))
    } else {
        if (!body.data?.teamId || body.data.userId != vars.get('userId') ||
            body.data.outTradeNo != vars.get('outTradeNo') ||
            body.data.activityId.toString() != props.getProperty('activity.id')) {
            throw new IllegalStateException('Wrong settlement identity')
        }
        if (label == 'settle-leader') vars.put('teamId', String.valueOf(body.data.teamId))
        else if (body.data.teamId != vars.get('teamId')) throw new IllegalStateException('Wrong joined team')
    }
} catch (Exception failure) {
    vars.put('flow.ok', 'false')
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage(failure.getMessage())
}
