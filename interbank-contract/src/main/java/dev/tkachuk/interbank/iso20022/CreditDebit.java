package dev.tkachuk.interbank.iso20022;

/** Which way a statement entry or balance goes, from the account owner's side. */
public enum CreditDebit {
    CRDT, // money in
    DBIT  // money out
}
