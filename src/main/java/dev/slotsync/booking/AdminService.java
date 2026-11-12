package dev.slotsync.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public AdminService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.transaction=new TransactionTemplate(manager);
    }

    static BookingService.BookingError error(int status,String code,String message) {
        return new BookingService.BookingError(status,code,message);
    }
    void actor(UUID actor,String role) {
        var rows=jdbc.queryForList("SELECT role,enabled FROM users WHERE id=? FOR UPDATE",actor);
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")))
            throw error(401,"LOGIN_REQUIRED","Sign in again.");
        if(!role.equals(rows.getFirst().get("role"))) throw error(403,"ACCESS_DENIED","This role is required: "+role);
    }
    public Policy currentPolicy() {
        return jdbc.queryForObject("SELECT v.* FROM policy_versions v JOIN policy_current c ON c.version_id=v.id",
                (rs,row)->new Policy(rs.getLong("id"),rs.getInt("max_duration_minutes"),rs.getBoolean("approval_required")));
    }
    public Policy adminPolicy(UUID actor) {
        return transaction.execute(status->{ actor(actor,"ADMIN"); return currentPolicy(); });
    }
    public Policy savePolicy(UUID actor,long expectedVersion,int maxMinutes,boolean approvalRequired) {
        if(maxMinutes<1 || maxMinutes>1440) throw error(400,"INVALID_POLICY","Maximum duration must be 1–1440 minutes.");
        return transaction.execute(status->{
            actor(actor,"ADMIN");
            long previous=jdbc.queryForObject("SELECT version_id FROM policy_current FOR UPDATE",Long.class);
            if(previous!=expectedVersion) throw error(409,"POLICY_CHANGED","Policy changed. Refresh before saving.");
            Policy old=currentPolicy();
            if(old.maxDurationMinutes()==maxMinutes && old.approvalRequired()==approvalRequired) return old;
            long id=jdbc.queryForObject("INSERT INTO policy_versions(max_duration_minutes,approval_required,created_by) VALUES (?,?,?) RETURNING id",
                    Long.class,maxMinutes,approvalRequired,actor);
            jdbc.update("UPDATE policy_current SET version_id=?",id);
            Policy next=new Policy(id,maxMinutes,approvalRequired);
            audit(actor,"POLICY_CHANGED",Long.toString(id),encode(Map.of("previousVersion",previous,"policy",next)));
            return next;
        });
    }

    public Batch preview(UUID actor,String csv) {
        if(csv==null || csv.getBytes(StandardCharsets.UTF_8).length>65536)
            throw error(400,"CSV_TOO_LARGE","CSV must be at most 64 KiB.");
        String hash=digest(csv);
        return transaction.execute(status->{
            actor(actor,"ADMIN");
            var existing=jdbc.queryForList("SELECT id FROM import_batches WHERE actor_id=? AND input_hash=?",actor,hash);
            if(!existing.isEmpty()) return batch(actor,(UUID)existing.getFirst().get("id"),false);
            Preview parsed=parseCsv(csv);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO import_batches(id,actor_id,input_hash,preview_json,valid) VALUES (?,?,?,?,?)",
                    id,actor,hash,encode(parsed),parsed.errors().isEmpty());
            return batch(actor,id,false);
        });
    }
    public List<Batch> imports(UUID actor) {
        return transaction.execute(status->{
            actor(actor,"ADMIN");
            return jdbc.queryForList("SELECT id FROM import_batches WHERE actor_id=? ORDER BY created_at DESC,id LIMIT 10",actor)
                    .stream().map(row->batch(actor,(UUID)row.get("id"),false)).toList();
        });
    }
    public Batch apply(UUID actor,UUID id) {
        try {
            return transaction.execute(status->{
                actor(actor,"ADMIN");
                jdbc.queryForList("SELECT id FROM import_batches WHERE id=? AND actor_id=? FOR UPDATE",id,actor);
                Batch batch=batch(actor,id,false);
                if(batch.applied()) return batch(actor,id,true);
                if(!batch.valid()) throw error(400,"INVALID_IMPORT","Fix all row errors and preview a new CSV before applying.");
                // Serialize import writers across actors; bookings keep their independent resource locks.
                jdbc.queryForList("SELECT pg_advisory_xact_lock(73409021)");
                for(ImportRow row:batch.preview().rows()) {
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM resources WHERE code=?)",Boolean.class,row.code())))
                        throw error(409,"IMPORT_CONFLICT","A resource code now exists. Nothing imported; preview revised CSV.");
                }
                for(ImportRow row:batch.preview().rows()) {
                    jdbc.update("INSERT INTO resources(id,code,display_name,type_code,location,capacity) VALUES (?,?,?,?,?,?)",
                            UUID.randomUUID(),row.code(),row.name(),row.type(),row.location(),row.capacity());
                }
                jdbc.update("UPDATE import_batches SET applied_at=current_timestamp,applied_count=? WHERE id=?",batch.preview().rows().size(),id);
                audit(actor,"RESOURCES_IMPORTED",id.toString(),encode(Map.of("inputRows",batch.preview().inputRows(),"inserted",batch.preview().rows().size())));
                return batch(actor,id,false);
            });
        } catch(DataAccessException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                if(cause instanceof SQLException sql && "23505".equals(sql.getSQLState()))
                    throw error(409,"IMPORT_CONFLICT","A resource code now exists. Nothing imported; preview revised CSV.");
            throw failure;
        }
    }
    private Batch batch(UUID actor,UUID id,boolean replayed) {
        var rows=jdbc.queryForList("SELECT * FROM import_batches WHERE id=? AND actor_id=?",id,actor);
        if(rows.isEmpty()) throw error(404,"IMPORT_NOT_FOUND","Import not found.");
        var row=rows.getFirst();
        Preview preview=decode((String)row.get("preview_json"),Preview.class);
        boolean applied=row.get("applied_at")!=null;
        return new Batch(id,preview,Boolean.TRUE.equals(row.get("valid")),applied,
                applied?((Number)row.get("applied_count")).intValue():0,replayed);
    }
    private Preview parseCsv(String source) {
        String csv=source.startsWith("\uFEFF")?source.substring(1):source;
        String[] lines=csv.split("\\r?\\n",-1);
        int length=lines.length;
        if(length>1 && lines[length-1].isEmpty()) length--;
        if(length<2 || length>101) throw error(400,"CSV_ROW_LIMIT","Provide one header and 1–100 resource rows.");
        try {
            if(!List.of("code","name","type","location","capacity").equals(fields(lines[0]))) throw new IllegalArgumentException();
        } catch(IllegalArgumentException failure) { throw error(400,"CSV_HEADER","Expected exactly: code,name,type,location,capacity"); }
        var rows=new ArrayList<ImportRow>(); var errors=new ArrayList<RowError>(); var seen=new HashSet<String>();
        for(int i=1;i<length;i++) {
            int number=i+1;
            try {
                List<String> f=fields(lines[i]);
                if(f.size()!=5) throw new IllegalArgumentException("Expected five columns.");
                String code=f.get(0),name=f.get(1),type=f.get(2),location=f.get(3);
                if(!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid code (uppercase letters, digits, _ or -).");
                if(!seen.add(code)) throw new IllegalArgumentException("Duplicate code in this CSV.");
                if(name.isBlank()||name.length()>160 || location.isBlank()||location.length()>200)
                    throw new IllegalArgumentException("Name/location must be nonempty and within 160/200 characters.");
                if(!List.of("ROOM","EQUIPMENT").contains(type)) throw new IllegalArgumentException("Type must be ROOM or EQUIPMENT.");
                if(!f.get(4).matches("[1-9][0-9]{0,5}")) throw new IllegalArgumentException("Capacity must be 1–999999.");
                int capacity=Integer.parseInt(f.get(4));
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM resources WHERE code=?)",Boolean.class,code)))
                    throw new IllegalArgumentException("Resource code already exists; this import creates resources only.");
                rows.add(new ImportRow(number,code,name,type,location,capacity));
            } catch(IllegalArgumentException failure) { errors.add(new RowError(number,failure.getMessage())); }
        }
        return new Preview(length-1,rows.size(),errors.size(),List.copyOf(rows),List.copyOf(errors));
    }
    // Deliberately bounded dialect: quoted commas/doubled quotes, no multiline cells.
    private static List<String> fields(String line) {
        if(line.length()>4096 || line.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Control characters/multiline cells or rows over 4096 characters are unsupported.");
        var result=new ArrayList<String>(); var cell=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int i=0;i<line.length();i++) {
            char ch=line.charAt(i);
            if(quoted) {
                if(ch=='"') {
                    if(i+1<line.length() && line.charAt(i+1)=='"') { cell.append('"'); i++; }
                    else { quoted=false; closed=true; }
                } else cell.append(ch);
            } else if(ch==',') { result.add(cell.toString().trim()); cell.setLength(0); closed=false; }
            else if(ch=='"' && cell.isEmpty() && !closed) quoted=true;
            else if(ch=='"' || closed) throw new IllegalArgumentException("Malformed CSV quoting.");
            else cell.append(ch);
        }
        if(quoted) throw new IllegalArgumentException("Unclosed quote; multiline cells are unsupported.");
        result.add(cell.toString().trim()); return result;
    }
    public List<AuditView> audit(UUID actor) {
        return transaction.execute(status->{
            actor(actor,"ADMIN");
            return jdbc.query("""
                SELECT a.id,u.username,a.action,a.entity_id,a.details,a.created_at FROM (
                  SELECT id,actor_id,action,booking_id::text entity_id,
                    json_build_object('from',previous_status,'to',new_status)::text details,created_at FROM audit_events
                  UNION ALL SELECT id,actor_id,action,entity_id,details_json,created_at FROM admin_audit_events
                ) a JOIN users u ON u.id=a.actor_id ORDER BY a.created_at DESC,a.id LIMIT 200
                """,(rs,row)->new AuditView(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("action"),
                    rs.getString("entity_id"),rs.getString("details"),rs.getObject("created_at",OffsetDateTime.class).toInstant().toString()));
        });
    }
    private void audit(UUID actor,String action,String entity,String details) {
        jdbc.update("INSERT INTO admin_audit_events(id,actor_id,action,entity_id,details_json) VALUES (?,?,?,?,?)",UUID.randomUUID(),actor,action,entity,details);
    }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(JsonProcessingException failure) {throw new IllegalStateException(failure);}
    }
    private <T> T decode(String value,Class<T> type) {
        try {return json.readValue(value,type);} catch(JsonProcessingException failure) {throw new IllegalStateException(failure);}
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }
    public record Policy(long version,int maxDurationMinutes,boolean approvalRequired) {}
    public record ImportRow(int row,String code,String name,String type,String location,int capacity) {}
    public record RowError(int row,String message) {}
    public record Preview(int inputRows,int acceptedRows,int rejectedRows,List<ImportRow> rows,List<RowError> errors) {}
    public record Batch(UUID id,Preview preview,boolean valid,boolean applied,int inserted,boolean replayed) {}
    public record AuditView(UUID id,String actor,String action,String entityId,String details,String createdAt) {}
}
