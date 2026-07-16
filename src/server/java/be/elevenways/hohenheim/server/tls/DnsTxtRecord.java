package be.elevenways.hohenheim.server.tls;

/** One DNS-01 TXT value that must exist while an ACME authorization runs. */
public record DnsTxtRecord(String name, String value) {}
