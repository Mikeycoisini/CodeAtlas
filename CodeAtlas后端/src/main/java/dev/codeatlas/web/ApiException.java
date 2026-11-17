package dev.codeatlas.web;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public static ApiException missing() { return new ApiException(404, "NOT_FOUND", "项目、快照或索引记录不存在"); }
}
