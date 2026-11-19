package dev.tkachuk.partnerbank;

/** Puts a statement file where bank-core's bank collects it. */
interface StatementUpload {

    void upload(String fileName, String xml);
}
