package pl.hackyeah.controllayer.guard.secrets;

/** Trafienie sekretu: reguła i zakres {@code [start, end)} samego sekretu w skanowanym tekście. */
public record SecretFinding(String ruleId, int start, int end) {}
