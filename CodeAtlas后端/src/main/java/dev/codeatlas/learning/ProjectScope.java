package dev.codeatlas.learning;

import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;

/** Request project is optional for legacy clients. Never assign ambiguous legacy records. */
public final class ProjectScope {
    private ProjectScope() {}
    public static String current() {
        var attrs=RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes request)) return "";
        String value=request.getRequest().getParameter("projectId");
        return value==null ? "" : value.trim();
    }
    public static boolean matches(Object owner) { return current().isEmpty() || current().equals(owner); }
    public static String owner(AtlasStore store, Object sourceIds, Object knowledgeId) {
        String owner=current();
        if (!owner.isEmpty()) store.one("SELECT id FROM projects WHERE id=?",owner);
        var owners=new LinkedHashSet<String>();
        if (!owner.isEmpty()) owners.add(owner);
        if (sourceIds instanceof List<?> ids) for (Object id:ids)
            owners.add((String)store.one("SELECT project_id FROM evidence_sources WHERE id=?",id).get("projectId"));
        if (knowledgeId instanceof String id && !id.isBlank()) {
            Object value=store.one("SELECT project_id FROM learning_records WHERE id=? AND kind='KNOWLEDGE'",id).get("projectId");
            if(value!=null) owners.add((String)value);
        }
        if(owners.size()>1 && current().isEmpty()) return null;
        if(owners.size()>1) throw new ApiException(400,"PROJECT_SOURCE_MISMATCH","来源不属于当前项目");
        return owners.isEmpty()?null:owners.iterator().next();
    }
    public static void unchanged(Object before,Object after) {
        if(before!=null && after!=null && !Objects.equals(before,after)) throw new ApiException(400,"PROJECT_SOURCE_MISMATCH","不能改变资料的项目归属");
    }
}
