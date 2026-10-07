package com.brainserve.appointment.integration.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/** Test-only HTTP endpoints exercise the actual native client, headers, body caps and deadlines. */
public final class GoogleCalendarTestServer implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor=Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<Response> replies=new ConcurrentLinkedQueue<>();
    private final List<Request> requests=new CopyOnWriteArrayList<>();
    public GoogleCalendarTestServer() {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{
                requests.add(new Request(exchange.getRequestMethod(),exchange.getRequestURI().toString(),exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("If-Match"),new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                Response response=replies.poll();if(response==null)response=new Response(500,"{}",Map.of(),0);
                response.headers().forEach((key,value)->exchange.getResponseHeaders().add(key,value));
                byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(response.status(),bytes.length==0?-1:bytes.length);
                    if(response.stallMillis()>0) {
                        // Headers and one body byte arrive promptly; only the remaining body stalls.
                        exchange.getResponseBody().write(bytes,0,1);exchange.getResponseBody().flush();Thread.sleep(response.stallMillis());
                        exchange.getResponseBody().write(bytes,1,bytes.length-1);
                    }else if(bytes.length>0)exchange.getResponseBody().write(bytes);
                }catch(IOException disconnected) { /* Client cancellation is expected in timeout/cap tests. */ }
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
                finally {exchange.close();}
            });server.start();
        }catch(IOException unavailable) {throw new IllegalStateException(unavailable);}
    }
    public GoogleHttpTransport transport(ObjectMapper json) {return new GoogleHttpTransport(json,uri("/token"),uri("/revoke"),uri("/calendar/v3/calendars"));}
    public URI uri(String path) {return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path);}
    public void reply(int status,String body) {reply(status,body,Map.of());}
    public void reply(int status,String body,Map<String,String> headers) {replies.add(new Response(status,body,headers,0));}
    public void stall(String body,long millis) {replies.add(new Response(200,body,Map.of(),millis));}
    public List<Request> requests() {return List.copyOf(requests);}
    public void reset() {requests.clear();replies.clear();}
    public void close() {server.stop(0);executor.shutdownNow();}
    public record Request(String method,String path,String authorization,String ifMatch,String body) { @Override public String toString(){return "TestRequest["+method+" "+path+"]";} }
    private record Response(int status,String body,Map<String,String> headers,long stallMillis) {}
}
