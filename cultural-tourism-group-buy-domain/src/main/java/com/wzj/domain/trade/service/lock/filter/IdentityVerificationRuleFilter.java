package com.wzj.domain.trade.service.lock.filter;

import com.wzj.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.wzj.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.wzj.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.wzj.types.design.framework.link.model2.handle.ILogicHandler;
import com.wzj.types.enums.ResponseCode;
import com.wzj.types.exception.AppException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** 实名票务校验。这里只校验格式；生产环境可在此节点接入实名核验防腐层。 */
@Service
public class IdentityVerificationRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    private static final Pattern ID_CARD = Pattern.compile("^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]$");
    private static final Pattern PASSPORT = Pattern.compile("^[A-Za-z0-9]{5,20}$");

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity request, TradeLockRuleFilterFactory.DynamicContext context) throws Exception {
        boolean basicValid = StringUtils.isNotBlank(request.getVisitorName())
                && request.getVisitorName().trim().length() <= 50
                && request.getTravelDate() != null
                && !request.getTravelDate().isBefore(LocalDate.now());
        String type = StringUtils.defaultString(request.getVisitorIdType()).toUpperCase();
        String idNo = StringUtils.defaultString(request.getVisitorIdNo());
        boolean idValid = ("ID_CARD".equals(type) && ID_CARD.matcher(idNo).matches())
                || ("PASSPORT".equals(type) && PASSPORT.matcher(idNo).matches());
        if (!basicValid || !idValid) {
            throw new AppException(ResponseCode.E0107);
        }
        return next(request, context);
    }
}
