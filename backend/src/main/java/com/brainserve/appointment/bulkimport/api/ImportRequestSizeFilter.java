package com.brainserve.appointment.bulkimport.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.Map;

/** Bounds JSON transport as well as decoded CSV, including chunked request bodies. */
@Component
@Order(1)
public class ImportRequestSizeFilter extends OncePerRequestFilter {
    public static final long MAX_JSON_BYTES=6L*2_097_152+4096;
    private final ObjectMapper mapper;
    public ImportRequestSizeFilter(ObjectMapper mapper) {this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {return !request.getMethod().equals("POST")||!request.getRequestURI().equals(request.getContextPath()+"/api/v1/bulk-imports/preview");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getContentLengthLong()>MAX_JSON_BYTES) {tooLarge(response);return;}
        try {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                private ServletInputStream bounded;
                @Override public BufferedReader getReader() throws IOException {return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
                @Override public ServletInputStream getInputStream() throws IOException {
                    if (bounded != null) return bounded;
                    ServletInputStream input=super.getInputStream();
                    bounded = new ServletInputStream() {
                        long read;
                        @Override public int read() throws IOException {int b=input.read();if(b!=-1&&++read>MAX_JSON_BYTES) { request.setAttribute("brainserve.import.body.tooLarge", Boolean.TRUE); throw new LimitExceeded(); }return b;}
                        @Override public int read(byte[] b,int off,int len) throws IOException {int n=input.read(b,off,(int)Math.min(len,MAX_JSON_BYTES-read+1));if(n>0&&(read+=n)>MAX_JSON_BYTES) { request.setAttribute("brainserve.import.body.tooLarge", Boolean.TRUE); throw new LimitExceeded(); }return n;}
                        @Override public boolean isFinished() {return input.isFinished();}
                        @Override public boolean isReady() {return input.isReady();}
                        @Override public void setReadListener(ReadListener listener) {input.setReadListener(listener);}
                    };
                    return bounded;
                }
            },response);
        } catch(LimitExceeded ex) {tooLarge(response);}
    }
    private void tooLarge(HttpServletResponse response) throws IOException {response.setStatus(413);response.setContentType("application/problem+json");mapper.writeValue(response.getOutputStream(),Map.of("status",413,"errorCode","IMPORT_BODY_TOO_LARGE","detail","Import request exceeds the bounded JSON transport limit"));}
    private static final class LimitExceeded extends IOException {}
}
