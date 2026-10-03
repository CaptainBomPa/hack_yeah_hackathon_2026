package pl.hackyeah.controllayer.integration;

enum ResponsesOperation {
    GENERATE("responses"), COMPACT("responses/compact");

    private final String path;
    ResponsesOperation(String path) { this.path = path; }
    String path() { return path; }
}
