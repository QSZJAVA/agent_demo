package com.example.report.dispatch;
import com.example.report.permission.CurrentUser;
/** Gateways requiring explicit delegated identity, including asynchronous execution. */
public interface AuthenticatedDispatchGateway extends DispatchGateway {
    Outcome dispatch(CurrentUser user, DispatchRequest request);
    Lookup lookup(CurrentUser user,String requestId);
    default Lookup lookupForOperator(CurrentUser actor,String operatorId,String requestId) {
        if (!actor.userId().equals(operatorId)) throw com.example.report.common.ApiException.forbidden("网关不支持运营代核对");
        return lookup(actor,requestId);
    }
}
