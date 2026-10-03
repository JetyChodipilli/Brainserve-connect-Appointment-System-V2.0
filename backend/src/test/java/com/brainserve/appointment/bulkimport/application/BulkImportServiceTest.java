package com.brainserve.appointment.bulkimport.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkImportServiceTest {
    private static final List<String> COLUMNS=List.of("code","name");
    @Test void parsesBomQuotedCommasEscapedQuotesAndMultilineCells() {
        var rows=ImportCsv.parse("\ufeffcode,name\r\nAA,\"Alpha, \"\"team\"\"\"\r\nBB,\"Line one\nLine two\"\r\n",COLUMNS,Set.of());
        assertThat(rows).hasSize(2);assertThat(rows.getFirst().get("name")).isEqualTo("Alpha, \"team\"");assertThat(rows.getLast().get("name")).isEqualTo("Line one\nLine two");
    }
    @Test void rejectsUnknownPrivilegedAndRepeatedHeaders() {for(String csv:List.of("code,name,password\nAA,A,x","code,code\nAA,A","code,name,role\nAA,A,ROLE_CEO")) assertThatThrownBy(()->ImportCsv.parse(csv,COLUMNS,Set.of())).isInstanceOf(BusinessException.class);}
    @Test void rejectsMissingRequiredHeadersAndExtraCells() {for(String csv:List.of("name\nAlpha","code,name\nAA,A,B","code,name\nAA")) assertThatThrownBy(()->ImportCsv.parse(csv,COLUMNS,Set.of())).isInstanceOf(BusinessException.class);}
    @Test void optionalColumnsAreExplicitlyFilledWithoutIntroducingUntrustedKeys() {assertThat(ImportCsv.parse("code\nAA",COLUMNS,Set.of("name")).getFirst()).containsExactly(entry("code","AA"),entry("name",""));}
    @Test void malformedQuotesAreRejected() {for(String csv:List.of("code,name\nAA,\"Unclosed","code,name\nAA,A\"b","code,name\nAA,\"A\"x")) assertThatThrownBy(()->ImportCsv.parse(csv,COLUMNS,Set.of())).isInstanceOf(BusinessException.class);}
    @Test void malformedUnicodeAndControlCharactersAreRejected() {for(String csv:List.of("code,name\nAA,\ud800","code,name\nAA,A\u0000B","code,name\nAA,A\tB")) assertThatThrownBy(()->ImportCsv.parse(csv,COLUMNS,Set.of())).isInstanceOf(BusinessException.class);}
    @Test void rejectsOversizedBytesFieldsAndRows() {
        assertThatThrownBy(()->ImportCsv.parse("code,name\nAA,"+"x".repeat(ImportCsv.MAX_BYTES),COLUMNS,Set.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ImportCsv.parse("code,name\nAA,"+"x".repeat(2001),COLUMNS,Set.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ImportCsv.parse("code,name\n"+"AA,Alpha\n".repeat(1001),COLUMNS,Set.of())).isInstanceOf(BusinessException.class);
        assertThat(ImportCsv.parse("code,name\n"+"AA,Alpha\n".repeat(1000),COLUMNS,Set.of())).hasSize(1000);
    }
    @Test void spreadsheetFormulaExportsAreNeutralizedInAllCells() {for(String raw:List.of("=1+1","+SUM(A1)","-2+3","@SUM(A1)","  =1","\t=1","\r=1","\n=1")) assertThat(ImportCsv.exportCell(raw)).startsWith("\"'");assertThat(ImportCsv.exportCell("O\"Brien, Jr")).isEqualTo("\"O\"\"Brien, Jr\"");}
    @Test void currentSystemAdminDoesNotImplicitlyGainEmployeeOrVisitorBusinessImports() {
        var authority=mock(CurrentAccountAuthority.class);UUID actor=UUID.randomUUID();
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_SYSTEM_ADMIN",null,Set.of("SYSTEM_CONFIGURE")));
        var service=new BulkImportService(mock(JdbcTemplate.class),new ObjectMapper(),authority,null,null,null,mock(AuditService.class),mock(PlatformTransactionManager.class));
        assertThat(service.options(actor).allowedKinds()).containsExactly(BulkImportService.ImportKind.DEPARTMENTS);
        assertThatThrownBy(()->service.template(actor,BulkImportService.ImportKind.EMPLOYEES)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.template(actor,BulkImportService.ImportKind.VISITORS)).isInstanceOf(BusinessException.class);
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_SYSTEM_ADMIN",null,Set.of()));
        assertThat(service.options(actor).allowedKinds()).isEmpty();
    }
    @Test void everyDtoExplicitlyRetainsNullFieldsWhenGlobalMapperOmitsNulls() throws Exception {
        ObjectMapper mapper=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String output=mapper.writeValueAsString(new BulkImportService.ImportRow(2,"FAILED",Map.of(),List.of("Invalid row"),null));
        assertThat(output).contains("\"recordId\":null");
    }

    @Test void transportRejectsOversizedContentLengthBeforeReadingBody() throws Exception {
        var filter=new com.brainserve.appointment.bulkimport.api.ImportRequestSizeFilter(new ObjectMapper());
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/bulk-imports/preview") {
            @Override public long getContentLengthLong() {return com.brainserve.appointment.bulkimport.api.ImportRequestSizeFilter.MAX_JSON_BYTES+1;}
        };
        var response=new org.springframework.mock.web.MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(request,response,chain);assertThat(response.getStatus()).isEqualTo(413);verifyNoInteractions(chain);
    }
    @Test void chunkedTransportCannotBypassCountingLimit() throws Exception {
        var filter=new com.brainserve.appointment.bulkimport.api.ImportRequestSizeFilter(new ObjectMapper());
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/bulk-imports/preview") {
            @Override public long getContentLengthLong() {return -1;}
        };
        request.setContent(new byte[(int)com.brainserve.appointment.bulkimport.api.ImportRequestSizeFilter.MAX_JSON_BYTES+1]);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{byte[] b=new byte[8192];var input=req.getInputStream();while(input.read(b)>0) {}});
        assertThat(response.getStatus()).isEqualTo(413);assertThat(request.getAttribute("brainserve.import.body.tooLarge")).isEqualTo(true);
    }
}
