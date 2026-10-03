package com.brainserve.appointment.bulkimport.application;

import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Small RFC 4180 reader with hard limits; no spreadsheet execution or arbitrary headers. */
final class ImportCsv {
    static final int MAX_BYTES = 2_097_152;
    static final int MAX_ROWS = 1000;
    private ImportCsv() {}
    static List<Map<String,String>> parse(String csv,List<String> columns,Set<String> optional) {
        if(csv==null) throw invalid("CSV is required");
        try {
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(csv));
            if(encoded.remaining()>MAX_BYTES) throw invalid("CSV must be at most 2 MiB");
        } catch(CharacterCodingException ex) { throw invalid("CSV must contain valid UTF-8 text"); }
        if(csv.startsWith("\ufeff")) csv=csv.substring(1);
        List<List<String>> raw=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder cell=new StringBuilder();
        boolean quoted=false,closed=false,started=false;
        for(int i=0;i<csv.length();i++) {
            char ch=csv.charAt(i);
            if((Character.isISOControl(ch)&&ch!='\r'&&ch!='\n')||ch=='\ufeff'||ch=='\u0000') throw invalid("CSV contains unsupported control characters");
            if(quoted) {
                if(ch=='"') { if(i+1<csv.length()&&csv.charAt(i+1)=='"') {cell.append('"');i++;} else {quoted=false;closed=true;} }
                else cell.append(ch);
            } else if(ch==','||ch=='\n'||ch=='\r') {
                row.add(cell.toString().trim());cell.setLength(0);started=false;closed=false;
                if(ch!=',') {raw.add(List.copyOf(row));row.clear(); if(ch=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n') i++;}
            } else if(ch=='"') {
                if(started||closed||cell.length()>0) throw invalid("CSV quotes are malformed");
                quoted=true;started=true;
            } else { if(closed) throw invalid("Unexpected content after a quoted field"); cell.append(ch);started=true; }
            if(cell.length()>2000) throw invalid("CSV field exceeds 2000 characters");
            if(row.size()>columns.size()||raw.size()>MAX_ROWS+1) throw invalid("CSV exceeds the allowed columns or 1000 rows");
        }
        if(quoted) throw invalid("CSV has an unclosed quoted field");
        if(started||closed||cell.length()>0||!row.isEmpty()) {row.add(cell.toString().trim());raw.add(List.copyOf(row));}
        if(raw.size()<2) throw invalid("CSV needs a header and at least one data row");
        List<String> headers=raw.getFirst();
        if(new HashSet<>(headers).size()!=headers.size()||headers.stream().anyMatch(h->!columns.contains(h))||columns.stream().filter(c->!optional.contains(c)).anyMatch(c->!headers.contains(c))) throw invalid("CSV headers must match the template; privileged or unknown columns are not allowed");
        if(raw.size()-1>MAX_ROWS) throw invalid("CSV must contain at most 1000 data rows");
        List<Map<String,String>> result=new ArrayList<>();
        for(List<String> values:raw.subList(1,raw.size())) {
            if(values.size()!=headers.size()) throw invalid("Each CSV row must have exactly the header's number of columns");
            Map<String,String> map=new LinkedHashMap<>();columns.forEach(c->map.put(c,""));
            for(int i=0;i<headers.size();i++) map.put(headers.get(i),values.get(i));
            result.add(Collections.unmodifiableMap(map));
        }
        return List.copyOf(result);
    }
    static String exportCell(String raw) {
        String value=raw==null?"":raw;
        // Neutralize both direct formulas and ones hidden by whitespace/control prefixes.
        String stripped=value.stripLeading();
        if(!stripped.isEmpty()&&"=+-@".indexOf(stripped.charAt(0))>=0 || !value.isEmpty()&&(Character.isWhitespace(value.charAt(0))||Character.isISOControl(value.charAt(0)))) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private static BusinessException invalid(String message) {return new BusinessException("IMPORT_CSV_INVALID",message,HttpStatus.BAD_REQUEST);}
}
