package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.AttachmentStorage;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual MVC range conversion and DB/FILE uploads; no network service or production filesystem mutation. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AttachmentDownloadBoundaryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BureauConfig config;
    @Autowired AttachmentStorage storage;
    @Autowired CaseService cases;
    @Autowired ClueService clues;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager tm;
    @Autowired org.springframework.core.env.Environment environment;
    final Map<String,String> original=new LinkedHashMap<>();
    final List<Stored> ownAttachments=new ArrayList<>();
    Path fileRoot;
    static final byte[] BYTES="0123456789ABCDEFGHIJKLMNOPQRSTUV".getBytes(StandardCharsets.US_ASCII);
    record Actor(String username,String token){}
    record Stored(long id,String path){}

    @BeforeEach void setup()throws IOException {
        assertNotEquals("ybcase",jdbc.queryForObject("select current_database()",String.class));
        SecurityContextHolder.clearContext();
        Path project=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if(!Files.isRegularFile(project.resolve("AGENTS.md")))project=project.getParent();
        assertTrue(Files.isRegularFile(project.resolve("AGENTS.md")),"Expected the established project directory");
        fileRoot=project.resolve("data/recovery/material-followup-20261027/attachment-test-files").resolve(UUID.randomUUID().toString()).normalize();
        assertFalse(fileRoot.toString().toLowerCase(Locale.ROOT).contains("onedrive"));
        Files.createDirectories(fileRoot);
        cfg("case_view_scope","SELF");cfg("attachment_dir",fileRoot.toString());cfg("attachment_storage","DB");
    }
    @AfterEach void cleanup()throws IOException {
        for(var attachment:ownAttachments){
            jdbc.update("delete from case_attachment where id=?",attachment.id());
            if(attachment.path()!=null){
                Path path=Path.of(attachment.path()).toAbsolutePath().normalize();
                assertTrue(path.startsWith(fileRoot),"Delete only this test's owned generated file");
                Files.deleteIfExists(path);
            }
        }
        original.forEach((key,value)->{jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));});
        SecurityContextHolder.clearContext();
    }
    void cfg(String key,String value){
        original.computeIfAbsent(key,k->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));
    }
    Actor actor(){
        var user=new SysUser();user.setUsername("attachment_boundary_"+System.nanoTime());user.setRealName("附件边界回归");user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode("HANDLER").orElseThrow());user=users.saveAndFlush(user);
        return new Actor(user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    long fresh(Actor owner){
        long cause=causes.findAll().stream().filter(item->item.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","附件边界回归","PROVIDER",null,null,null,null,"隔离下载与上传边界测试",BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),owner.username()).getId();
    }
    long clue(Actor owner){
        return clues.create(new ClueService.ClueCreateReq("COMPLAINT","来源附件边界回归","隔离医院","PROVIDER",LocalDate.now(),owner.username()),owner.username()).getId();
    }
    long upload(String source,long sourceId,Actor owner,String name)throws Exception {
        var response=mvc.perform(multipart("/api/bureau/"+source+"/{id}/attachments",sourceId)
                .file(new MockMultipartFile("file",name,"application/octet-stream",BYTES)).param("category","OTHER")
                .header("Authorization","Bearer "+owner.token())).andReturn().getResponse();
        var body=mapper.readTree(response.getContentAsString());assertEquals(0,body.path("code").asInt(),body.toString());
        long id="cases".equals(source)?jdbc.queryForObject("select max(id) from case_attachment where case_id=?",Long.class,sourceId):body.path("data").path("id").asLong();
        ownAttachments.add(new Stored(id,jdbc.queryForObject("select file_path from case_attachment where id=?",String.class,id)));return id;
    }
    String downloadPath(long id,boolean source){return source?"/api/bureau/precase-attachments/"+id+"/download":"/api/bureau/cases/attachments/"+id+"/download";}
    MockHttpServletResponse download(long id,Actor actor,String range,boolean source)throws Exception {
        var request=get(downloadPath(id,source)).header("Authorization","Bearer "+actor.token());
        if(range!=null)request.header(HttpHeaders.RANGE,range);
        return mvc.perform(request).andReturn().getResponse();
    }
    void assertRange(MockHttpServletResponse response,int start,int end){
        assertEquals(206,response.getStatus());
        assertEquals("bytes "+start+"-"+end+"/"+BYTES.length,response.getHeader(HttpHeaders.CONTENT_RANGE));
        assertEquals(Integer.toString(end-start+1),response.getHeader(HttpHeaders.CONTENT_LENGTH));
        assertArrayEquals(Arrays.copyOfRange(BYTES,start,end+1),response.getContentAsByteArray());
    }
    void assertMultipart(MockHttpServletResponse response){
        assertEquals(206,response.getStatus());
        var type=org.springframework.http.MediaType.parseMediaType(Objects.requireNonNull(response.getContentType()));
        assertEquals("multipart",type.getType());assertEquals("byteranges",type.getSubtype());assertNotNull(type.getParameter("boundary"));
        byte[] body=response.getContentAsByteArray();String text=new String(body,StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("Content-Range: bytes 0-2/"+BYTES.length));
        assertTrue(text.contains("Content-Range: bytes 8-11/"+BYTES.length));
        assertTrue(text.contains("\r\n\r\n012\r\n"));assertTrue(text.contains("\r\n\r\n89AB\r\n"));
        assertFalse(text.contains(new String(BYTES,StandardCharsets.US_ASCII)),"A multipart request must not receive the whole original");
        String length=response.getHeader(HttpHeaders.CONTENT_LENGTH);
        assertTrue(length==null||Long.parseLong(length)==body.length,"Multipart framing cannot retain the unframed file Content-Length: "+length+" vs "+body.length);
    }

    @Test void ordinaryDatabaseAndFileDownloadsKeepExactBytesLengthAndDisposition()throws Exception {
        Actor owner=actor();long cid=fresh(owner);
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);String name="边界原件 "+mode+".bin";long aid=upload("cases",cid,owner,name);
            var response=download(aid,owner,null,false);assertEquals(200,response.getStatus());
            assertEquals(Integer.toString(BYTES.length),response.getHeader(HttpHeaders.CONTENT_LENGTH));assertArrayEquals(BYTES,response.getContentAsByteArray());
            assertEquals(name,ContentDisposition.parse(Objects.requireNonNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))).getFilename());
        }
    }
    @Test void configuredContainerPartHeaderBoundSupportsTheExisting255CodePointFilenameLimit(){
        var properties=org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("server",org.springframework.boot.autoconfigure.web.ServerProperties.class).get();
        long bound=properties.getTomcat().getMaxPartHeaderSize().toBytes();
        assertEquals(2048L,bound,"Multipart part headers must use the explicit bounded configuration, not Tomcat's 512B default or unlimited");
        String filename="\uD83D\uDE00".repeat(255);
        assertEquals(255,filename.codePointCount(0,filename.length()));
        assertEquals(1020,filename.getBytes(StandardCharsets.UTF_8).length);
        String headers="Content-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\n"
                +"Content-Type: application/"+"x".repeat(116)+"\r\n\r\n";
        assertTrue(headers.getBytes(StandardCharsets.UTF_8).length<=bound,"A valid maximum-width UTF-8 name and allowed MIME header must fit");
        assertTrue(properties.getTomcat().getMaxPartCount()>0,"The per-request part count remains bounded");
    }
    @Test void singleSuffixAndOpenRangesReturnOnlyTheRequestedBytesInBothStorageModes()throws Exception {
        Actor owner=actor();long cid=fresh(owner);
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);long aid=upload("cases",cid,owner,"single-range.bin");
            assertRange(download(aid,owner,"bytes=1-4",false),1,4);
            assertRange(download(aid,owner,"bytes=-4",false),BYTES.length-4,BYTES.length-1);
            assertRange(download(aid,owner,"bytes=3-",false),3,BYTES.length-1);
        }
    }
    @Test void multipartRangesDoNotAdvertiseTheUnframedFullFileLength()throws Exception {
        Actor owner=actor();long cid=fresh(owner);
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);long aid=upload("cases",cid,owner,"multi-range.bin");
            assertMultipart(download(aid,owner,"bytes=0-2,8-11",false));
        }
    }
    @Test void invalidAndUnsatisfiableRangesHaveAnEmpty416Response()throws Exception {
        Actor owner=actor();long cid=fresh(owner);
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);long aid=upload("cases",cid,owner,"invalid-range.bin");
            for(String range:List.of("bytes=999999-","bytes=8-2","items=0-1","bytes=--","bytes=","bytes=0-2,99999-")){
                var response=download(aid,owner,range,false);assertEquals(416,response.getStatus(),range);
                assertEquals("bytes */"+BYTES.length,response.getHeader(HttpHeaders.CONTENT_RANGE));
                assertEquals("0",response.getHeader(HttpHeaders.CONTENT_LENGTH));assertEquals(0,response.getContentAsByteArray().length);
            }
        }
    }
    @Test void sourceDownloadsUseTheSameSingleMultipartAndUnsatisfiableRangeRules()throws Exception {
        Actor owner=actor();long clue=clue(owner);
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);long aid=upload("clues",clue,owner,"source-range.bin");
            assertRange(download(aid,owner,"bytes=1-4",true),1,4);assertMultipart(download(aid,owner,"bytes=0-2,8-11",true));
            var invalid=download(aid,owner,"bytes=99999-",true);assertEquals(416,invalid.getStatus());
            assertEquals("0",invalid.getHeader(HttpHeaders.CONTENT_LENGTH));assertEquals(0,invalid.getContentAsByteArray().length);
        }
    }
    @Test void anOutOfScopeRangeRequestDoesNotExposeOriginalBytesOrContentRange()throws Exception {
        Actor owner=actor(),outsider=actor();long cid=fresh(owner),clue=clue(owner);
        long caseAid=upload("cases",cid,owner,"private-case.bin"),sourceAid=upload("clues",clue,owner,"private-source.bin");
        for(boolean source:List.of(false,true)){
            var response=download(source?sourceAid:caseAid,outsider,"bytes=99999-",source);
            assertEquals(2080,mapper.readTree(response.getContentAsString()).path("code").asInt());
            assertNull(response.getHeader(HttpHeaders.CONTENT_RANGE));assertFalse(Arrays.equals(BYTES,response.getContentAsByteArray()));
        }
    }
    @Test void aVoidedRangeRequestCannotExposeOriginalBytesOrFileLength()throws Exception {
        Actor owner=actor();long cid=fresh(owner),clue=clue(owner);
        long caseAid=upload("cases",cid,owner,"void-case.bin"),sourceAid=upload("clues",clue,owner,"void-source.bin");
        for(boolean source:List.of(false,true)){
            long aid=source?sourceAid:caseAid;String path=source?"/api/bureau/precase-attachments/"+aid+"/void":"/api/bureau/cases/"+cid+"/attachments/"+aid+"/void";
            var voided=mvc.perform(post(path).header("Authorization","Bearer "+owner.token()).contentType("application/json")
                    .content("{\"reason\":\"隔离误传留痕\"}")).andReturn().getResponse();
            assertEquals(0,mapper.readTree(voided.getContentAsString()).path("code").asInt());
            var response=download(aid,owner,"bytes=0-2",source);assertEquals(2135,mapper.readTree(response.getContentAsString()).path("code").asInt());
            assertNull(response.getHeader(HttpHeaders.CONTENT_RANGE));assertFalse(Arrays.equals(BYTES,response.getContentAsByteArray()));
        }
    }
    @Test void nulVoidReasonsLeaveBothCaseAndSourceRowsAndOriginalsUnchanged()throws Exception {
        Actor owner=actor();long cid=fresh(owner),clue=clue(owner);cfg("attachment_storage","FILE");
        long caseAid=upload("cases",cid,owner,"nul-case.bin"),sourceAid=upload("clues",clue,owner,"nul-source.bin");
        for(boolean source:List.of(false,true)){
            long aid=source?sourceAid:caseAid;String before=jdbc.queryForObject("select md5(row_to_json(a)::text) from case_attachment a where id=?",String.class,aid);
            String path=source?"/api/bureau/precase-attachments/"+aid+"/void":"/api/bureau/cases/"+cid+"/attachments/"+aid+"/void";
            for(String reason:List.of("\u0000","误传\u0000材料")){
                var response=mvc.perform(post(path).header("Authorization","Bearer "+owner.token()).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("reason",reason)))).andReturn().getResponse();
                assertEquals(2130,mapper.readTree(response.getContentAsString()).path("code").asInt());
                assertEquals(before,jdbc.queryForObject("select md5(row_to_json(a)::text) from case_attachment a where id=?",String.class,aid));
            }
            assertArrayEquals(BYTES,download(aid,owner,null,source).getContentAsByteArray());
        }
    }
    @Test void validMaximumNamesIncludingSupplementaryCharactersWorkInDatabaseAndFileModes()throws Exception {
        Actor owner=actor();long cid=fresh(owner);
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);
            for(String name:List.of("n".repeat(251)+".bin","\uD83D\uDE00".repeat(126)+".bin")){
                long aid=upload("cases",cid,owner,name);assertEquals(name,jdbc.queryForObject("select filename from case_attachment where id=?",String.class,aid));
                var response=download(aid,owner,null,false);assertEquals(200,response.getStatus());assertArrayEquals(BYTES,response.getContentAsByteArray());
                assertEquals(name,ContentDisposition.parse(Objects.requireNonNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))).getFilename());
                if("FILE".equals(mode)){
                    Path path=Path.of(jdbc.queryForObject("select file_path from case_attachment where id=?",String.class,aid));
                    assertTrue(path.getFileName().toString().length()<=255,"A valid display name must not make the storage component exceed filesystem limits");
                    assertArrayEquals(BYTES,Files.readAllBytes(path));
                }
            }
        }
    }
    @Test void excessFilenameCodePointsProduceNoRowsOrFilesInEitherMode()throws Exception {
        Actor owner=actor();long cid=fresh(owner);int before=jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid);
        for(String mode:List.of("DB","FILE")){
            cfg("attachment_storage",mode);
            for(String name:List.of("n".repeat(256),"\uD83D\uDE00".repeat(256))){
                var response=mvc.perform(multipart("/api/bureau/cases/{id}/attachments",cid).file(new MockMultipartFile("file",name,"application/octet-stream",BYTES))
                        .header("Authorization","Bearer "+owner.token())).andReturn().getResponse();
                assertEquals(2054,mapper.readTree(response.getContentAsString()).path("code").asInt());
                assertEquals(before,jdbc.queryForObject("select count(*) from case_attachment where case_id=?",Integer.class,cid));
            }
        }
        try(var paths=Files.walk(fileRoot)){assertEquals(0,paths.filter(Files::isRegularFile).count());}
    }
    @Test void wildcardMimeMetadataFallsBackToAConcreteBinaryContentType(){
        for(String mime:List.of("*/*","text/*")){
            var row=new HashMap<String,Object>();row.put("filename","legacy.bin");row.put("data",BYTES);row.put("size_bytes",BYTES.length);row.put("content_type",mime);
            assertEquals("application/octet-stream",Objects.requireNonNull(storage.response(row).getHeaders().getContentType()).toString());
        }
    }
    @Test void invalidConfiguredStoragePathProducesAGenericErrorWithoutThePrivatePath(){
        BureauConfig isolated=mock(BureauConfig.class);when(isolated.str("attachment_storage","DB")).thenReturn("FILE");
        when(isolated.str("attachment_dir","./data/attachments")).thenReturn("private-material-volume\u0000unreadable");
        var isolatedStorage=new AttachmentStorage(jdbc,isolated);
        var error=assertThrows(BizException.class,()->new TransactionTemplate(tm).execute(status->{
            try{return isolatedStorage.store(1L,null,null,null,null,new MockMultipartFile("file","valid.bin","application/octet-stream",BYTES),"OTHER","isolated");}
            catch(IOException e){throw new UncheckedIOException(e);}
        }));
        assertEquals(2054,error.code);assertFalse(error.getMessage().contains("private-material-volume"));
    }
}
