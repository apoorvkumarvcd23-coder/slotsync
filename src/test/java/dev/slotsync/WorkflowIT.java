package dev.slotsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    static final String ROOM="10000000-0000-0000-0000-000000000001";
    static final String HEADER="code,name,type,location,capacity\n";
    static final String HASH=new BCryptPasswordEncoder(4).encode("synthetic-workflow-password");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {FoundationIT.database(r);}
    static UUID id(String name) {return UUID.nameUUIDFromBytes(("workflow-"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @BeforeEach void fixtures() {
        TestData.reset(jdbc);
        for(String name:List.of("alice","bob","approver","approver2","admin","admin2")) {
            String role=name.startsWith("admin")?"ADMIN":name.startsWith("approver")?"APPROVER":"REQUESTER";
            jdbc.update("INSERT INTO users(id,username,password_hash,role) VALUES (?,?,?,?)",id(name),name,HASH,role);
        }
    }

    @Test void policyIsProspectiveAndSnapshotsAreRetained() throws Exception {
        Session admin=login("admin"),alice=login("alice");
        JsonNode old=body(create(alice,1,60));
        long original=jdbc.queryForObject("SELECT policy_version_id FROM bookings WHERE id=?::uuid",Long.class,old.get("id").asText());
        long version=body(policy(admin,1,30,false)).get("version").asLong();
        assertThat(version).isGreaterThan(original);
        MvcResult tooLong=create(alice,2,31);
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tooLong).get("code").asText()).isEqualTo("DURATION_EXCEEDED");
        JsonNode fresh=body(create(alice,3,30));
        assertThat(fresh.get("status").asText()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT policy_version_id FROM bookings WHERE id=?::uuid",Long.class,old.get("id").asText())).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT policy_version_id FROM bookings WHERE id=?::uuid",Long.class,fresh.get("id").asText())).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?::uuid",String.class,old.get("id").asText())).isEqualTo("PENDING");
        mvc.perform(get("/api/policy")).andExpect(status().isOk()).andExpect(jsonPath("$.maxDurationMinutes").value(30));
        mvc.perform(auth(get("/api/admin/audit"),admin)).andExpect(jsonPath("$[?(@.action=='POLICY_CHANGED')]").isNotEmpty());
    }

    @Test void typedPolicyBoundsStaleEditsAndUnchangedSavesAreSafe() throws Exception {
        Session admin=login("admin");
        for(String value:List.of("null","0","1441","1.5","\"30\"","true")) {
            assertThat(post(admin,"/api/admin/policy","{\"expectedVersion\":1,\"maxDurationMinutes\":"+value+",\"approvalRequired\":true}").getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(post(admin,"/api/admin/policy","{\"expectedVersion\":1,\"maxDurationMinutes\":30,\"approvalRequired\":\"false\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(policy(admin,1,1440,true).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("policy_versions")).isEqualTo(1);
        assertThat(count("admin_audit_events")).isZero();
        policy(admin,1,120,true);
        assertThat(policy(admin,1,60,false).getResponse().getStatus()).isEqualTo(409);
        assertThat(count("policy_versions")).isEqualTo(2);
        assertThat(count("admin_audit_events")).isEqualTo(1);
    }

    @Test void concurrentPolicyEditorsCannotLoseAnUpdate() throws Exception {
        Session a=login("admin"),b=login("admin2");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->policy(a,1,30,true).getResponse().getStatus());
            var y=pool.submit(()->policy(b,1,60,false).getResponse().getStatus());
            assertThat(List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(count("policy_versions")).isEqualTo(2);
    }

    @Test void approvalRejectAndRepeatHaveAtomicAuditAndSlotSemantics() throws Exception {
        Session alice=login("alice"),approver=login("approver");
        String id=body(create(alice,1,60)).get("id").asText();
        mvc.perform(auth(get("/api/approvals"),approver)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].owner").value("alice")).andExpect(jsonPath("$[0].policyVersion").value(1));
        for(int i=0;i<2;i++) assertThat(decide(approver,id,"REJECTED").getResponse().getStatus()).isEqualTo(200);
        assertThat(decide(approver,id,"APPROVED").getResponse().getStatus()).isEqualTo(409);
        assertThat(count("audit_events")).isEqualTo(2);
        assertThat(create(login("bob"),1,60).getResponse().getStatus()).isEqualTo(201);
        String approved=body(create(alice,2,60)).get("id").asText();
        decide(approver,approved,"APPROVED");
        assertThat(post(alice,"/api/bookings/"+approved+"/cancel",null).getResponse().getStatus()).isEqualTo(200);
        assertThat(decide(approver,approved,"APPROVED").getResponse().getStatus()).isEqualTo(409);
    }

    @Test void competingDecisionsCommitOnlyOneTransition() throws Exception {
        String id=body(create(login("alice"),1,60)).get("id").asText();
        Session a=login("approver"),b=login("approver2");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->decide(a,id,"APPROVED").getResponse().getStatus());
            var y=pool.submit(()->decide(b,id,"REJECTED").getResponse().getStatus());
            assertThat(List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(count("audit_events")).isEqualTo(2);
    }

    @Test void ownerWhoChangesRoleStillCannotSelfApprove() throws Exception {
        String id=body(create(login("alice"),1,60)).get("id").asText();
        jdbc.update("UPDATE users SET role='APPROVER' WHERE username='alice'");
        assertThat(decide(login("alice"),id,"APPROVED").getResponse().getStatus()).isEqualTo(403);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test void approvalAuditFailureRollsBackStatus() throws Exception {
        String id=body(create(login("alice"),1,60)).get("id").asText();
        Session approver=login("approver");
        failAudit("audit_events");
        try {
            assertThat(decide(approver,id,"APPROVED").getResponse().getStatus()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?::uuid",String.class,id)).isEqualTo("PENDING");
            assertThat(count("audit_events")).isEqualTo(1);
        } finally {restoreAudit("audit_events");}
        assertThat(decide(approver,id,"APPROVED").getResponse().getStatus()).isEqualTo(200);
    }

    @Test void quotedCsvPreviewAtomicApplyAndReplayReconcile() throws Exception {
        Session admin=login("admin");
        String csv="\uFEFFcode,name,type,location,capacity\r\nROOM-CEDAR,\"Cedar, studio\",ROOM,\"East \"\"demo\"\" wing\",12\r\nKIT-CAMERA,Camera,EQUIPMENT,Desk,1\r\n";
        JsonNode preview=body(preview(admin,csv));
        assertThat(preview.get("valid").asBoolean()).isTrue();
        assertThat(preview.at("/preview/inputRows").asInt()).isEqualTo(2);
        assertThat(preview.at("/preview/rows/0/name").asText()).isEqualTo("Cedar, studio");
        assertThat(count("resources")).isEqualTo(3);
        assertThat(body(preview(admin,csv)).get("id")).isEqualTo(preview.get("id"));
        String path="/api/admin/imports/"+preview.get("id").asText()+"/apply";
        JsonNode applied=body(post(admin,path,null));
        assertThat(applied.get("inserted").asInt()).isEqualTo(2);
        assertThat(applied.get("replayed").asBoolean()).isFalse();
        assertThat(body(post(admin,path,null)).get("replayed").asBoolean()).isTrue();
        assertThat(count("resources")).isEqualTo(5);
        assertThat(count("admin_audit_events")).isEqualTo(1);
        mvc.perform(auth(get("/api/admin/imports"),admin)).andExpect(jsonPath("$[0].applied").value(true));
    }

    @Test void invalidRowsCannotPartiallyApplyAndCountsAreVisible() throws Exception {
        Session admin=login("admin");
        String csv=HEADER+"ROOM-NEW,New,ROOM,Campus,8\nROOM-NEW,Duplicate,ROOM,Campus,2\nKIT-PROJECTOR,Existing,EQUIPMENT,Desk,1\nBAD,Bad,UNKNOWN,Desk,2\nZERO,Zero,ROOM,Campus,0\n";
        JsonNode p=body(preview(admin,csv));
        assertThat(p.get("valid").asBoolean()).isFalse();
        assertThat(p.at("/preview/inputRows").asInt()).isEqualTo(5);
        assertThat(p.at("/preview/acceptedRows").asInt()).isEqualTo(1);
        assertThat(p.at("/preview/rejectedRows").asInt()).isEqualTo(4);
        assertThat(post(admin,"/api/admin/imports/"+p.get("id").asText()+"/apply",null).getResponse().getStatus()).isEqualTo(400);
        assertThat(count("resources")).isEqualTo(3);
        assertThat(count("admin_audit_events")).isZero();
    }

    @Test void headerLimitsAndMalformedCsvHaveBoundedErrors() throws Exception {
        Session admin=login("admin");
        for(String csv:List.of("wrong,header\nA,B", "\"bad\nX,Y",HEADER+"A,Name,ROOM,Place,1\n".repeat(101), HEADER+"X".repeat(65536))) {
            assertThat(preview(admin,csv).getResponse().getStatus()).isEqualTo(400);
        }
        for(String row:List.of("../ESCAPE,Name,ROOM,Place,1","X,\"unclosed,ROOM,Place,1","X,Name,ROOM,Place,1,extra","X,Na\tme,ROOM,Place,1","X,Name,ROOM,Place,9999999999999999")) {
            JsonNode p=body(preview(admin,HEADER+row));
            assertThat(p.get("valid").asBoolean()).isFalse();
            assertThat(p.at("/preview/errors/0/row").asInt()).isEqualTo(2);
        }
        assertThat(count("resources")).isEqualTo(3);
    }

    @Test void concurrentImportsWithSameCodeHaveNoPartialWrites() throws Exception {
        Session a=login("admin"),b=login("admin2");
        String x=body(preview(a,HEADER+"ROOM-UNIQUE,Unique,ROOM,Campus,2\nROOM-SHARED,Shared,ROOM,Campus,2")).get("id").asText();
        String y=body(preview(b,HEADER+"ROOM-SHARED,Shared,ROOM,Campus,2")).get("id").asText();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->post(a,"/api/admin/imports/"+x+"/apply",null).getResponse().getStatus());
            var two=pool.submit(()->post(b,"/api/admin/imports/"+y+"/apply",null).getResponse().getStatus());
            assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        int inserted=jdbc.queryForObject("SELECT sum(applied_count) FROM import_batches",Integer.class);
        assertThat(count("resources")).isEqualTo(3+inserted);
        assertThat(count("admin_audit_events")).isEqualTo(1);
    }

    @Test void importAndPolicyAuditFailuresRollBackAndRetrySafely() throws Exception {
        Session admin=login("admin");
        String id=body(preview(admin,HEADER+"ROOM-ROLLBACK,Rollback,ROOM,Campus,3")).get("id").asText();
        failAudit("admin_audit_events");
        try {
            assertThat(post(admin,"/api/admin/imports/"+id+"/apply",null).getResponse().getStatus()).isEqualTo(503);
            assertThat(count("resources")).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT applied_at IS NULL FROM import_batches WHERE id=?::uuid",Boolean.class,id)).isTrue();
            assertThat(policy(admin,1,60,false).getResponse().getStatus()).isEqualTo(503);
            assertThat(count("policy_versions")).isEqualTo(1);
        } finally {restoreAudit("admin_audit_events");}
        assertThat(post(admin,"/api/admin/imports/"+id+"/apply",null).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("resources")).isEqualTo(4);
    }

    @Test void privilegedRoutesEnforceCsrfRoleLiveAccountAndBatchOwnership() throws Exception {
        Session admin=login("admin"),other=login("admin2"),requester=login("alice"),approver=login("approver");
        mvc.perform(get("/api/admin/policy")).andExpect(status().isUnauthorized());
        for(Session wrong:List.of(requester,approver)) {
            mvc.perform(auth(get("/api/admin/policy"),wrong)).andExpect(status().isForbidden());
            assertThat(preview(wrong,HEADER+"X,Name,ROOM,Campus,1").getResponse().getStatus()).isEqualTo(403);
        }
        mvc.perform(auth(get("/api/approvals"),requester)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/approvals"),admin)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/policy").session(admin.session).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        String id=body(preview(admin,HEADER+"X,Name,ROOM,Campus,1")).get("id").asText();
        assertThat(post(other,"/api/admin/imports/"+id+"/apply",null).getResponse().getStatus()).isEqualTo(404);
        mvc.perform(auth(get("/api/admin/imports"),other)).andExpect(jsonPath("$.length()").value(0));
        jdbc.update("UPDATE users SET enabled=false WHERE username='admin'");
        assertThat(post(admin,"/api/admin/imports/"+id+"/apply",null).getResponse().getStatus()).isEqualTo(401);
        jdbc.update("UPDATE users SET enabled=true,role='REQUESTER' WHERE username='admin'");
        mvc.perform(auth(get("/api/admin/audit"),admin)).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET role='REQUESTER' WHERE username='approver'");
        mvc.perform(auth(get("/api/approvals"),approver)).andExpect(status().isForbidden());
    }

    private Session login(String username) throws Exception {
        MvcResult initial=mvc.perform(get("/api/session")).andReturn();
        JsonNode token=body(initial);
        MockHttpSession session=(MockHttpSession)initial.getRequest().getSession(false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/login").session(session).header(token.get("csrfHeader").asText(),token.get("csrfToken").asText())
                .param("username",username).param("password","synthetic-workflow-password")).andExpect(status().isNoContent());
        JsonNode current=body(mvc.perform(get("/api/session").session(session)).andReturn());
        return new Session(session,current.get("csrfHeader").asText(),current.get("csrfToken").asText());
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,Session s) {return request.session(s.session).header(s.header,s.token);}
    private MvcResult post(Session s,String path,String payload) throws Exception {
        var request=auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path),s);
        if(payload!=null) request.contentType("application/json").content(payload);
        return mvc.perform(request).andReturn();
    }
    private MvcResult policy(Session s,long expected,int duration,boolean approval) throws Exception {
        return post(s,"/api/admin/policy",json.writeValueAsString(Map.of("expectedVersion",expected,"maxDurationMinutes",duration,"approvalRequired",approval)));
    }
    private MvcResult preview(Session s,String csv) throws Exception {return post(s,"/api/admin/imports/preview",json.writeValueAsString(Map.of("csv",csv)));}
    private MvcResult decide(Session s,String id,String decision) throws Exception {return post(s,"/api/approvals/"+id+"/decision",json.writeValueAsString(Map.of("decision",decision)));}
    private MvcResult create(Session s,int day,int minutes) throws Exception {
        Instant start=Instant.now().truncatedTo(ChronoUnit.DAYS).plus(day+1,ChronoUnit.DAYS).plusSeconds(36000);
        return mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/bookings"),s).header("Idempotency-Key","workflow-"+UUID.randomUUID()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("resourceId",ROOM,"startsAt",start,"endsAt",start.plusSeconds(minutes*60L))))).andReturn();
    }
    private JsonNode body(MvcResult r) throws Exception {return json.readTree(r.getResponse().getContentAsString());}
    private int count(String table) {
        if(!List.of("resources","policy_versions","admin_audit_events","audit_events").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);
    }
    private void failAudit(String table) {
        jdbc.execute("CREATE FUNCTION workflow_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER workflow_fail BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION workflow_fail()");
    }
    private void restoreAudit(String table) {jdbc.execute("DROP TRIGGER workflow_fail ON "+table);jdbc.execute("DROP FUNCTION workflow_fail()");}
    private record Session(MockHttpSession session,String header,String token) {}
}
