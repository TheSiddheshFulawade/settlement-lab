package com.siddheshfulawade.settlementlab.gl;

import com.siddheshfulawade.settlementlab.domain.Posting;

/** The downstream general ledger, in domain terms. Keyed by transfer id, so re-publishing is safe. */
public interface GeneralLedgerPort {

    enum Ack {
        /** Stored now. */
        RECORDED,
        /** Already stored by an earlier publish (for example one whose ack was lost). Not stored again. */
        ALREADY_RECORDED
    }

    /**
     * @throws GeneralLedgerException on a transient failure; the posting may or may not have been
     *     stored (a lost ack), so the caller must be ready to publish it again
     */
    Ack publish(Posting posting);
}
