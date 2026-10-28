package com.perm.kate.api;
public class KException extends Exception {
    public String captcha_img;
    public String captcha_sid;
    public final int error_code;
    public String redirect_uri;
    public final String url;
    public KException(String str, int i, String str2) { super(str); this.error_code = i; this.url = str2; }
}
