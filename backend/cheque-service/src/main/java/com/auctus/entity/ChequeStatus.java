package com.auctus.entity;

/**
 * A cheque is either good or it is not.
 *
 * <p>There used to be a REVIEW state for checks that could not be completed -
 * an unknown account holder, a signature service that did not answer, an amount
 * the reader could not make out. It was removed because a bank cannot leave a
 * cheque in limbo: an unverifiable signature is not an acceptance, so those
 * cases are rejected with a reason that says the check could not be completed
 * rather than pretending the document was forged. An administrator can overturn
 * any of them from the oversight screen.
 */
public enum ChequeStatus {
    PROCESSING,
    ACCEPTED,
    REJECTED
}
