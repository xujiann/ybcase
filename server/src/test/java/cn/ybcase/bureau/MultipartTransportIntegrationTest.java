package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real Tomcat multipart parsing, not MockMvc's already-parsed MockMultipartFile shortcut. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultipartTransportIntegrationTest {
    @Autowired ServletWebServerApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired BureauConfig config;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @LocalServerPort int port;
    final Map<String,String> original = new LinkedHashMap<>();
    final byte[] bytes = "small-multipart-original\n医保原件\u0000".getBytes(StandardCharsets.UTF_8);
    HttpClient client;
    String token, username;
    long caseId;
    Path folder;

    @BeforeEach void setup() throws Exception {
        assertNotEquals("ybcase",jdbc.queryForObject("select current_database()",String.class),"Transport tests require a disposable database");
        assertTrue(port>0 && port!=8090 && port!=8100,"Use only the embedded random test port");
        var tomcat=assertInstanceOf(TomcatWebServer.class,context.getWebServer());
        assertEquals(2048,tomcat.getTomcat().getConnector().getMaxPartHeaderSize(),"Exercise the bounded production2KB part-header configuration");
        Path project=Path.of("").toAbsolutePath().normalize();
        while(!Files.isDirectory(project.resolve("frontend")) && project.getParent()!=null)project=project.getParent();
        assertTrue(Files.isDirectory(project.resolve("frontend")) && Files.isDirectory(project.resolve("server")));
        folder=project.resolve("data/recovery/material-followup-20261027/embedded-multipart-"+UUID.randomUUID()).normalize();
        assertTrue(folder.startsWith(project) && !folder.toString().toLowerCase(Locale.ROOT).contains("onedrive"));
        Files.createDirectories(folder);
        cfg("attachment_storage","FILE");cfg("attachment_dir",folder.toString());
        var actor=new SysUser();username="multipart_"+UUID.randomUUID().toString().replace("-","");actor.setUsername(username);
        actor.setRealName("真实传输回归");actor.setPassword(encoder.encode("MultipartTest2026!"));actor.setEnabled(true);actor.setMustChangePassword(false);
        actor.getRoles().add(roles.findByCode("ADMIN").orElseThrow());users.saveAndFlush(actor);
        client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var login=client.send(HttpRequest.newBuilder(uri("/api/auth/login")).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("username",username,"password","MultipartTest2026!")),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,login.statusCode(),"Real test login must succeed once");
        var auth=mapper.readTree(login.body());assertEquals(0,auth.path("code").asInt());assertFalse(auth.path("data").path("mustChangePassword").asBoolean());
        token=auth.path("data").path("token").asText();assertFalse(token.isBlank());
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        caseId=cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","真实上传回归"+System.nanoTime(),"PROVIDER",null,null,null,null,"小文件传输",BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),username).getId();
    }
    @AfterEach void restore() {
        if(client!=null)client.close();
        original.forEach((key,value)->{jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));});
    }
    void cfg(String key,String value) {
        original.computeIfAbsent(key,k->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key);config.onConfigChanged(new ConfigChangedEvent(key));
    }
    URI uri(String path) {return URI.create("http://127.0.0.1:"+port+path);}
    String uploadPath() {return "/api/bureau/cases/"+caseId+"/attachments";}
    HttpResponse<byte[]> upload(String filename,int padding,boolean authenticated) throws Exception {
        String boundary="testBoundary"+UUID.randomUUID().toString().replace("-","");
        var body=new ByteArrayOutputStream();
        String header="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: application/octet-stream\r\n"
                +(padding==0?"":"X-Test-Padding: "+"x".repeat(padding)+"\r\n")+"\r\n";
        body.write(header.getBytes(StandardCharsets.UTF_8));body.write(bytes);
        body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        assertTrue(body.size()<10_000,"The failure fixture must be a small request, not a file-size load test");
        var request=HttpRequest.newBuilder(uri(uploadPath())).timeout(Duration.ofSeconds(15))
                .header("Accept","application/json").header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if(authenticated)request.header("Authorization","Bearer "+token);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode json(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200,response.statusCode(),"Multipart errors should remain explicit business JSON, not an error-page authentication failure");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        return mapper.readTree(response.body());
    }
    String rows() {
        return jdbc.queryForObject("select count(*)::text||':'||md5(coalesce(string_agg(row_to_json(t)::text,'' order by id),'')) from case_attachment t where case_id=?",String.class,caseId);
    }
    Map<String,String> files() throws Exception {
        var snapshot=new TreeMap<String,String>();
        try(var paths=Files.walk(folder)) {
            for(Path path:paths.filter(Files::isRegularFile).toList())
                snapshot.put(folder.relativize(path).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return snapshot;
    }
    void stillAuthenticated() throws Exception {
        var response=client.send(HttpRequest.newBuilder(uri("/api/auth/me")).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode());assertEquals(0,mapper.readTree(response.body()).path("code").asInt());
    }

    @Test void validLongUnicodeFilenameUploadsAndDownloadsThroughRealTomcat() throws Exception {
        String filename="😀".repeat(150)+".bin";
        assertEquals(0,json(upload(filename,0,true)).path("code").asInt());
        var row=jdbc.queryForMap("select id,filename,file_path,size_bytes from case_attachment where case_id=?",caseId);
        assertEquals(filename,row.get("filename"));assertEquals((long)bytes.length,((Number)row.get("size_bytes")).longValue());
        Path file=Path.of((String)row.get("file_path")).toAbsolutePath().normalize();assertTrue(file.startsWith(folder));assertArrayEquals(bytes,Files.readAllBytes(file));
        var download=client.send(HttpRequest.newBuilder(uri("/api/bureau/cases/attachments/"+row.get("id")+"/download"))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,download.statusCode());assertArrayEquals(bytes,download.body());
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").contains(URLEncoder.encode(filename,StandardCharsets.UTF_8)));
        stillAuthenticated();
    }

    @Test void rejectedOversizePartHeaderIsJson2054AndCannotInvalidateAuthenticationOrChangeOriginals() throws Exception {
        assertEquals(0,json(upload("existing-original.bin",0,true)).path("code").asInt());
        String beforeRows=rows();var beforeFiles=files();assertEquals(1,beforeFiles.size());
        var rejected=upload("tiny.bin",4096,true);assertEquals(2054,json(rejected).path("code").asInt());
        assertEquals(beforeRows,rows());assertEquals(beforeFiles,files());stillAuthenticated();
        String rid=rejected.headers().firstValue("X-Request-Id").orElseThrow();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int recorded=0;
        while(System.nanoTime()<deadline) {
            recorded=jdbc.queryForObject("select count(*) from sys_audit_log where username=? and path=? and request_id=? and http_status=2054",Integer.class,username,uploadPath(),rid);
            if(recorded==1)break;Thread.sleep(20);
        }
        assertEquals(1,recorded,"Pre-handler multipart rejection must be recorded as2054, not a misleading401");
        assertEquals(401,upload("anonymous.bin",0,false).statusCode());
        assertEquals(401,upload("anonymous-bad-header.bin",4096,false).statusCode(),"Multipart advice must not bypass authentication");
        assertEquals(beforeRows,rows());assertEquals(beforeFiles,files());stillAuthenticated();
    }
}
