package com.wzj.domain.trade.adapter.port;

/** Receives successful delivery confirmations; durable outbox rows remain the recovery source. */
public interface INotifyTaskStatusWriter {
    enum Disposition { PERSISTED, QUEUED, COALESCED }

    Disposition recordSuccess(String teamId);

    boolean isPending(String teamId);
}
