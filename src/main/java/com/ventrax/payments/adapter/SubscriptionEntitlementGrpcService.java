package com.ventrax.payments.adapter;

import com.ventrax.payments.application.BillingService;
import com.ventrax.payments.grpc.ProEntitlementRequest;
import com.ventrax.payments.grpc.ProEntitlementResponse;
import com.ventrax.payments.grpc.SubscriptionEntitlementServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.UUID;

@GrpcService(interceptors = InternalServiceTokenInterceptor.class)
public class SubscriptionEntitlementGrpcService
        extends SubscriptionEntitlementServiceGrpc.SubscriptionEntitlementServiceImplBase {

    private final BillingService billingService;

    public SubscriptionEntitlementGrpcService(BillingService billingService) {
        this.billingService = billingService;
    }

    @Override
    public void getProEntitlement(
            ProEntitlementRequest request,
            StreamObserver<ProEntitlementResponse> responseObserver) {
        try {
            UUID accountId = UUID.fromString(request.getAccountId());
            var entitlement = billingService.entitlement(accountId);
            boolean proActive = Boolean.TRUE.equals(entitlement.get("proActive"));

            responseObserver.onNext(ProEntitlementResponse.newBuilder()
                    .setProActive(proActive)
                    .setValidUntil(proActive ? toIsoInstant(entitlement.get("periodEndsAt")) : "")
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("account_id must be a UUID")
                    .asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Billing entitlement is unavailable")
                    .withCause(exception)
                    .asRuntimeException());
        }
    }

    private String toIsoInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return "";
    }
}
