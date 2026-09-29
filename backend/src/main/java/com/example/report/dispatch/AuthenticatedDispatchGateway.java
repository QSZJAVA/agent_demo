package com.example.report.dispatch;
import com.example.report.permission.CurrentUser;
/** Gateways requiring explicit delegated identity, including asynchronous execution. */
public interface AuthenticatedDispatchGateway extends DispatchGateway {
    Outcome dispatch(CurrentUser user, DispatchRequest request);
    Lookup lookup(CurrentUser user,String requestId);
}
