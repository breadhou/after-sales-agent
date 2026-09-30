package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/** Validated v1 case. Evaluator labels remain private and are never returned to business code. */
public final class CaseSpec {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    private static final Pattern CASE_ID = Pattern.compile("^[A-Z][A-Z0-9_-]{0,63}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_-]{0,63}$");
    private static final Pattern MONEY = Pattern.compile("^(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");
    private static final Set<String> CATEGORIES = set("NORMAL", "POLICY_CONFIRMATION", "ADVERSARIAL", "ABNORMAL", "KNOWLEDGE", "INDEPENDENT_REVIEW");
    private static final Set<String> MODES = set("LIVE_E2E", "CONTROLLED", "REVIEW_ONLY");
    private static final Set<String> ORDER_STATUSES = set("PENDING", "PAID", "SHIPPED", "DELIVERED", "RECEIVED", "REFUNDED", "CANCELLED");
    private static final Set<String> REFUND_STATES = set("NONE", "PENDING", "REFUNDED");
    private static final Set<String> OUTCOMES = set("REFUND_COMPLETED", "NOT_SUBMITTED", "NEEDS_CONFIRMATION", "NEEDS_ORDER_SELECTION", "CANCELLED", "REVIEW_APPROVED", "REVIEW_REJECTED", "ANSWERED", "ERROR", "SKIPPED", "UNRESOLVED_WRITE", "ESCALATED");
    private static final Set<String> TARGETS = set("AGENT_CHAIN", "MCP_CONTRACT", "BACKEND_TRANSACTION");
    private static final Set<String> POINTS = set("NONE", "MODEL_SCRIPT", "MCP_BEFORE_REQUEST", "MCP_AFTER_RESPONSE", "MCP_RESPONSE", "SOURCE_BEFORE_FINAL", "BACKEND_PROBE");
    private static final Set<String> COMPONENTS = set("DIALOGUE", "REVIEW", "EXPLANATION", "MCP", "BACKEND");
    private static final Set<String> MODEL_ROLES = set("DIALOGUE", "REVIEW", "EXPLANATION");
    private static final Set<String> COMPONENT_STATES = set("REAL", "SUBSTITUTED", "ABSENT");
    private static final Set<String> PROBES = set("ROLLBACK_AFTER_INSERT", "CONCURRENT_IDEMPOTENCY", "LEGACY_PENDING", "STALE_POLICY");
    private static final Set<String> MCP_TOOLS = set("get_order", "list_user_orders", "get_logistics", "get_refund_eligibility", "list_policy_clauses", "list_on_shelf_products", "get_product_detail", "submit_refund");
    private static final Set<String> EVENTS = set("SUBMIT_BEFORE_CONFIRMATION", "SUBMIT_WITHOUT_REVIEW", "CROSS_USER_ORDER_READ", "WRITE_RETRY_AFTER_UNKNOWN", "SUCCESS_CLAIM_WITHOUT_REFUND", "REVIEW_INCOMPLETE_AS_REJECTION", "OUT_OF_ALLOWLIST_SOURCE", "EXTRA_EXPLANATION_CALL", "WRONG_ORDER_SUBMIT", "EXECUTOR_AVAILABLE");
    private static final Set<String> EVIDENCE = set("POLICY_CLAUSE", "FAQ", "PRODUCT", "CODE_CONTRACT", "FIXTURE");
    private final JsonNode original;

    private CaseSpec(JsonNode value) { original = value.deepCopy(); }

    public static CaseSpec parse(JsonNode value) {
        validateCase(value);
        return new CaseSpec(value);
    }

    /** Return a detached business-facing copy with both evaluator-only fields removed. */
    public JsonNode document() {
        ObjectNode copy = (ObjectNode) original.deepCopy();
        copy.remove("expect");
        copy.remove("manualRubric");
        return copy;
    }

    private static void validateCase(JsonNode c) {
        obj(c, "case", set("schemaVersion", "caseId", "category", "mode", "tags", "variationRationale", "fixture", "turns", "expect", "manualRubric"), set("control", "reviewInput"));
        if (integer(c.get("schemaVersion"), "case.schemaVersion", 1) != 1) fail("case.schemaVersion", "must be 1");
        pattern(c.get("caseId"), CASE_ID, "case.caseId");
        String category = en(c.get("category"), CATEGORIES, "case.category");
        String mode = en(c.get("mode"), MODES, "case.mode");
        JsonNode tags = array(c.get("tags"), "case.tags", 0);
        List<String> tagList = new ArrayList<>();
        for (int i=0;i<tags.size();i++) tagList.add(pattern(tags.get(i), ALIAS, "case.tags["+i+"]"));
        unique(tagList, "case.tags");
        text(c.get("variationRationale"), "case.variationRationale", true);
        JsonNode fixture = c.get("fixture");
        Fixture keys = fixture(fixture, "case.fixture", mode.equals("REVIEW_ONLY"));
        if (mode.equals("REVIEW_ONLY")) {
            if (!category.equals("INDEPENDENT_REVIEW")) fail("case.category", "REVIEW_ONLY requires INDEPENDENT_REVIEW");
            if (!c.has("reviewInput") || c.has("control")) fail("case", "REVIEW_ONLY requires reviewInput and forbids control");
            review(c.get("reviewInput"), "case.reviewInput");
            boolean synthetic = c.path("reviewInput").path("synthetic").asBoolean();
            if (synthetic && !fixture.isEmpty()) fail("case.fixture", "synthetic review requires empty fixture");
            if (!synthetic && fixture.isEmpty()) fail("case.fixture", "non-synthetic review requires facts");
            if (array(c.get("turns"), "case.turns", 0).size()!=0) fail("case.turns", "REVIEW_ONLY has no conversation turns");
        } else {
            if (category.equals("INDEPENDENT_REVIEW")) fail("case.category", "reserved for REVIEW_ONLY");
            if (c.has("reviewInput")) fail("case.reviewInput", "only valid for REVIEW_ONLY");
            if (mode.equals("LIVE_E2E") && c.has("control")) fail("case.control", "LIVE_E2E forbids injection");
            if (mode.equals("CONTROLLED")) {
                if (!c.has("control")) fail("case.control", "CONTROLLED requires explicit control");
                control(c.get("control"), "case.control");
            }
            JsonNode turns = array(c.get("turns"), "case.turns", 1);
            String active = text(fixture.get("activeActor"), "case.fixture.activeActor", true);
            Map<String,String> sessions = new HashMap<>();
            for (int i=0;i<turns.size();i++) {
                String p="case.turns["+i+"]";
                JsonNode t=obj(turns.get(i),p,set("sessionAlias","actorAlias","input"),Set.of());
                String session=pattern(t.get("sessionAlias"),ALIAS,p+".sessionAlias");
                String actor=pattern(t.get("actorAlias"),ALIAS,p+".actorAlias");
                if (!keys.actors.contains(actor)) fail(p+".actorAlias","must name declared actor");
                if (!actor.equals(active)) fail(p+".actorAlias","v1 turns must use active actor");
                if (sessions.containsKey(session) && !sessions.get(session).equals(actor)) fail(p+".actorAlias","session cannot switch users");
                sessions.put(session,actor);
                for (String alias : placeholders(text(t.get("input"),p+".input",true),p+".input"))
                    if (!keys.orders.contains(alias) && !keys.products.contains(alias)) fail(p+".input","contains unbound logical key");
            }
        }
        expected(c.get("expect"), "case.expect");
        Iterator<String> expectOrders=c.path("expect").path("orders").fieldNames();
        while(expectOrders.hasNext()) if(!keys.orders.contains(expectOrders.next())) fail("case.expect.orders","must reference fixture order");
        rubric(c.get("manualRubric"),"case.manualRubric");
    }

    private record Fixture(Set<String> actors, Set<String> orders, Set<String> products) { }

    private static Fixture fixture(JsonNode f,String p,boolean allowEmpty) {
        if(allowEmpty && f!=null && f.isObject() && f.isEmpty()) return new Fixture(Set.of(),Set.of(),Set.of());
        obj(f,p,set("activeActor","actors","orders","products"),Set.of());
        JsonNode actors=array(f.get("actors"),p+".actors",1); List<String> actorList=new ArrayList<>();
        for(int i=0;i<actors.size();i++) actorList.add(pattern(actors.get(i),ALIAS,p+".actors["+i+"]"));
        unique(actorList,p+".actors"); String active=pattern(f.get("activeActor"),ALIAS,p+".activeActor");
        if(!actorList.contains(active)) fail(p+".activeActor","must name declared actor");
        JsonNode orders=map(f.get("orders"),p+".orders"), products=map(f.get("products"),p+".products");
        Set<String> aliasOverlap=new HashSet<>(fields(orders)); aliasOverlap.retainAll(fields(products));
        if(!aliasOverlap.isEmpty()) fail(p,"order and product aliases must be globally disjoint");
        if(orders.isEmpty()||products.isEmpty()) fail(p,"business fixture requires orders and products");
        Map<String,BigDecimal> prices=new HashMap<>(); Set<String> skus=new HashSet<>();
        Iterator<Map.Entry<String,JsonNode>> pi=products.fields();
        while(pi.hasNext()) {
            var e=pi.next(); String alias=pattern(MAPPER.getNodeFactory().textNode(e.getKey()),ALIAS,p+".products key");
            String pp=p+".products."+alias; JsonNode prod=e.getValue(); String src=text(prod.get("source"),pp+".source",true);
            if(src.equals("RUN_MUTABLE")) {
                obj(prod,pp,set("source","name","description","skus"),Set.of()); text(prod.get("name"),pp+".name",true); text(prod.get("description"),pp+".description",true);
                JsonNode rows=array(prod.get("skus"),pp+".skus",1);
                for(int i=0;i<rows.size();i++) {
                    String sp=pp+".skus["+i+"]"; JsonNode sku=obj(rows.get(i),sp,set("skuAlias","price","stock","specs"),Set.of());
                    String sa=pattern(sku.get("skuAlias"),ALIAS,sp+".skuAlias"); if(!skus.add(sa)) fail(sp+".skuAlias","duplicate SKU alias");
                    prices.put(sa,money(sku.get("price"),sp+".price",false)); integer(sku.get("stock"),sp+".stock",0);
                    JsonNode specs=sku.get("specs"); if(!specs.isObject()) fail(sp+".specs","must be object");
                    Iterator<Map.Entry<String,JsonNode>> si=specs.fields(); while(si.hasNext()){var spec=si.next(); text(MAPPER.getNodeFactory().textNode(spec.getKey()),sp+".specs key",true); text(spec.getValue(),sp+".specs."+spec.getKey(),false);}
                }
            } else if(src.equals("DEMO_READONLY")) {
                obj(prod,pp,set("source","logicalKey"),Set.of()); String logical=pattern(prod.get("logicalKey"),Pattern.compile("^[a-z][a-z0-9_]{0,63}$"),pp+".logicalKey");
                JsonNode catalog=demoCatalog().get(logical); if(catalog==null) fail(pp+".logicalKey","not in read-only demo catalog");
                JsonNode rows=catalog.path("skus"); for(int i=0;i<rows.size();i++){String sa=alias+"-sku-"+String.format(Locale.ROOT,"%02d",i+1);if(!skus.add(sa))fail(pp,"duplicate SKU alias");prices.put(sa,money(rows.get(i).get("price"),pp+".catalog SKU price",false));}
            } else fail(pp+".source","unknown product source");
        }
        Iterator<Map.Entry<String,JsonNode>> oi=orders.fields();
        while(oi.hasNext()) {
            var e=oi.next(); String op=p+".orders."+e.getKey(); JsonNode o=obj(e.getValue(),op,set("owner","status","ageSeconds","items","expectedPaidAmount","existingRefund"),Set.of());
            String owner=pattern(o.get("owner"),ALIAS,op+".owner"); if(!actorList.contains(owner))fail(op+".owner","must name declared actor");
            en(o.get("status"),ORDER_STATUSES,op+".status"); integer(o.get("ageSeconds"),op+".ageSeconds",0); en(o.get("existingRefund"),REFUND_STATES,op+".existingRefund");
            JsonNode items=array(o.get("items"),op+".items",1); BigDecimal total=BigDecimal.ZERO;
            for(int i=0;i<items.size();i++){String ip=op+".items["+i+"]";JsonNode item=obj(items.get(i),ip,set("skuAlias","quantity"),Set.of());String sku=pattern(item.get("skuAlias"),ALIAS,ip+".skuAlias");int q=integer(item.get("quantity"),ip+".quantity",1);if(!prices.containsKey(sku))fail(ip+".skuAlias","undeclared SKU");total=total.add(prices.get(sku).multiply(BigDecimal.valueOf(q)));}
            BigDecimal expected=money(o.get("expectedPaidAmount"),op+".expectedPaidAmount",false); if(total.compareTo(expected)!=0)fail(op+".expectedPaidAmount","must equal independently calculated total");
        }
        return new Fixture(Set.copyOf(actorList),fields(orders),fields(products));
    }

    private static Map<String,JsonNode> demoCatalog() {
        Path directory=Path.of("").toAbsolutePath().normalize();
        while(directory!=null) {
            Path path=directory.resolve(Path.of("data","demo-products.json"));
            if(Files.isRegularFile(path)) {
                try {
                    JsonNode rows=MAPPER.readTree(Files.readString(path));
                    Map<String,JsonNode> out=new HashMap<>();
                    if(rows!=null&&rows.isArray()) for(JsonNode row:rows) if(row.path("logicalKey").isTextual()) out.put(row.path("logicalKey").asText(),row);
                    return out;
                } catch(IOException e) { fail("data/demo-products.json","cannot read catalog"); }
            }
            directory=directory.getParent();
        }
        fail("data/demo-products.json","catalog is missing"); return Map.of();
    }
    private static void control(JsonNode c,String p) {
        c=obj(c,p,set("target","components","usesRealModel","point"),set("toolName","toolCalls","script","response","probe"));
        String target=en(c.get("target"),TARGETS,p+".target"), point=en(c.get("point"),POINTS,p+".point");
        JsonNode cn=obj(c.get("components"),p+".components",COMPONENTS,Set.of()); Map<String,String> states=new HashMap<>();
        for(String role:COMPONENTS)states.put(role,en(cn.get(role),COMPONENT_STATES,p+".components."+role));
        if(!c.get("usesRealModel").isBoolean())fail(p+".usesRealModel","must be boolean");
        boolean real=MODEL_ROLES.stream().anyMatch(role->states.get(role).equals("REAL")); if(real!=c.get("usesRealModel").booleanValue())fail(p+".usesRealModel","must match model roles");
        Set<String> payload=new HashSet<>(fields(c)); payload.removeAll(set("target","components","usesRealModel","point"));
        if(target.equals("BACKEND_TRANSACTION")) {
            if(!point.equals("BACKEND_PROBE")||!payload.equals(set("probe")))fail(p,"backend transaction requires one probe");
            if(!states.get("BACKEND").equals("REAL")||c.get("usesRealModel").booleanValue())fail(p,"backend probe requires real backend and no real model"); en(c.get("probe"),PROBES,p+".probe");
        } else if(target.equals("MCP_CONTRACT")) {
            if(!point.equals("NONE")||!payload.equals(set("toolCalls")))fail(p,"MCP_CONTRACT requires direct toolCalls and point NONE");
            if(!states.get("MCP").equals("REAL")||!states.get("BACKEND").equals("REAL")||MODEL_ROLES.stream().anyMatch(role->!states.get(role).equals("ABSENT"))||c.get("usesRealModel").booleanValue())fail(p,"MCP_CONTRACT requires real MCP/backend and absent model roles");
            toolCalls(c.get("toolCalls"),p+".toolCalls");
        } else {
            if(point.equals("NONE")||point.equals("BACKEND_PROBE")||payload.contains("probe")||payload.contains("toolCalls"))fail(p,"AGENT_CHAIN requires one supported injection");
            switch(point) {
                case "MODEL_SCRIPT" -> {if(!payload.equals(set("script")))fail(p,"MODEL_SCRIPT requires script");script(c.get("script"),p+".script",states);}
                case "MCP_AFTER_RESPONSE","MCP_RESPONSE" -> {if(!payload.equals(set("toolName","response")))fail(p,"response injection requires toolName and response");en(c.get("toolName"),MCP_TOOLS,p+".toolName");text(c.get("response"),p+".response",false);}
                case "MCP_BEFORE_REQUEST" -> {if(!payload.equals(set("toolName")))fail(p,"request injection requires toolName");en(c.get("toolName"),MCP_TOOLS,p+".toolName");}
                case "SOURCE_BEFORE_FINAL" -> {if(!payload.equals(set("response")))fail(p,"source injection requires response");text(c.get("response"),p+".response",false);}
                default -> fail(p,"unsupported Agent-chain injection");
            }
        }
    }

    private static void script(JsonNode s,String p,Map<String,String> states) {
        s=obj(s,p,set("role","responses"),Set.of());String role=en(s.get("role"),MODEL_ROLES,p+".role");if(!states.get(role).equals("SUBSTITUTED"))fail(p+".role","must be substituted");
        JsonNode responses=array(s.get("responses"),p+".responses",1); Pattern tool=Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
        for(int i=0;i<responses.size();i++){String rp=p+".responses["+i+"]";JsonNode r=obj(responses.get(i),rp,Set.of(),set("text","toolCalls"));if(r.isEmpty())fail(rp,"requires text and/or toolCalls");if(r.has("text"))text(r.get("text"),rp+".text",false);if(r.has("toolCalls")){if(!role.equals("DIALOGUE"))fail(rp+".toolCalls","only DIALOGUE may request tools");JsonNode calls=array(r.get("toolCalls"),rp+".toolCalls",1);for(int j=0;j<calls.size();j++){String cp=rp+".toolCalls["+j+"]";JsonNode call=obj(calls.get(j),cp,set("name","arguments"),Set.of());pattern(call.get("name"),tool,cp+".name");if(!call.get("arguments").isObject())fail(cp+".arguments","must be JSON object");}}}
    }

    private static void toolCalls(JsonNode value,String p) {
        JsonNode calls=array(value,p,1);
        for(int i=0;i<calls.size();i++){
            String cp=p+"["+i+"]";JsonNode call=obj(calls.get(i),cp,set("toolName","arguments"),Set.of());String name=en(call.get("toolName"),MCP_TOOLS,cp+".toolName");
            Set<String> required; Set<String> optional=Set.of();
            switch(name){case "get_order","get_logistics","get_refund_eligibility"->required=set("orderId");case "get_product_detail"->required=set("productId");case "list_on_shelf_products"->required=set("pageNum","pageSize");case "submit_refund"->required=set("orderId","reason","expectedCatalogFingerprint","expectedPolicyCode");case "list_user_orders"->{required=Set.of();optional=set("status");}default->required=Set.of();}
            JsonNode args=obj(call.get("arguments"),cp+".arguments",required,optional);
            for(String field:set("orderId","productId"))if(args.has(field))placeholder(args.get(field),cp+".arguments."+field);
            if(args.has("status"))en(args.get("status"),ORDER_STATUSES,cp+".arguments.status");
            for(String field:set("pageNum","pageSize"))if(args.has(field))integer(args.get(field),cp+".arguments."+field,1);
            for(String field:set("reason","expectedCatalogFingerprint","expectedPolicyCode"))if(args.has(field))text(args.get(field),cp+".arguments."+field,true);
        }
    }

    private static void review(JsonNode v,String p) {
        v=obj(v,p,set("synthetic","originalUserRequest","trustedOrder","trustedEligibility","candidateAction","policyEvidence"),set("pairId"));
        if(!v.get("synthetic").isBoolean())fail(p+".synthetic","must be boolean");
        for(String f:set("originalUserRequest","trustedOrder","trustedEligibility"))text(v.get(f),p+"."+f,true);
        if(v.has("pairId"))pattern(v.get("pairId"),CASE_ID,p+".pairId");
        JsonNode action=obj(v.get("candidateAction"),p+".candidateAction",set("orderId","reason"),Set.of());positiveId(action.get("orderId"),p+".candidateAction.orderId");text(action.get("reason"),p+".candidateAction.reason",true);
        JsonNode evidence=obj(v.get("policyEvidence"),p+".policyEvidence",set("fingerprint","code","title","clauseText"),Set.of());
        for(String f:set("fingerprint","title","clauseText"))text(evidence.get(f),p+".policyEvidence."+f,true);pattern(evidence.get("code"),CODE,p+".policyEvidence.code");
    }

    private static void expected(JsonNode v,String p) {
        v=obj(v,p,set("outcome","orders","basis","forbiddenEvents"),set("policyCodes","requiredSources"));en(v.get("outcome"),OUTCOMES,p+".outcome");
        JsonNode orders=map(v.get("orders"),p+".orders");Iterator<Map.Entry<String,JsonNode>> oi=orders.fields();
        while(oi.hasNext()){var e=oi.next();String op=p+".orders."+e.getKey();JsonNode o=obj(e.getValue(),op,set("orderStatus","newRefundRows","refundAmount","ownerMatches"),Set.of());en(o.get("orderStatus"),ORDER_STATUSES,op+".orderStatus");integer(o.get("newRefundRows"),op+".newRefundRows",0);if(!o.get("refundAmount").isNull())money(o.get("refundAmount"),op+".refundAmount",true);if(!o.get("ownerMatches").isBoolean())fail(op+".ownerMatches","must be boolean");}
        JsonNode basis=array(v.get("basis"),p+".basis",1);for(int i=0;i<basis.size();i++){String bp=p+".basis["+i+"]";JsonNode b=obj(basis.get(i),bp,set("kind","key","rationale"),Set.of());en(b.get("kind"),EVIDENCE,bp+".kind");text(b.get("key"),bp+".key",true);text(b.get("rationale"),bp+".rationale",true);}
        JsonNode forbidden=array(v.get("forbiddenEvents"),p+".forbiddenEvents",0);List<String> events=new ArrayList<>();for(int i=0;i<forbidden.size();i++)events.add(en(forbidden.get(i),EVENTS,p+".forbiddenEvents["+i+"]"));unique(events,p+".forbiddenEvents");
        for(String f:set("policyCodes","requiredSources"))if(v.has(f)){JsonNode values=array(v.get(f),p+"."+f,0);List<String> strings=new ArrayList<>();for(int i=0;i<values.size();i++)strings.add(text(values.get(i),p+"."+f+"["+i+"]",true));unique(strings,p+"."+f);}
    }

    private static void rubric(JsonNode v,String p) {
        v=obj(v,p,set("version","criteria"),Set.of());en(v.get("version"),set("v1"),p+".version");JsonNode criteria=array(v.get("criteria"),p+".criteria",0);Set<String> ids=new HashSet<>();
        for(int i=0;i<criteria.size();i++){String cp=p+".criteria["+i+"]";JsonNode c=obj(criteria.get(i),cp,set("criterionId","question","requiredFacts","forbiddenClaims"),Set.of());String id=pattern(c.get("criterionId"),CODE,cp+".criterionId");if(!ids.add(id))fail(cp+".criterionId","must be unique");text(c.get("question"),cp+".question",true);for(String f:set("requiredFacts","forbiddenClaims")){JsonNode values=array(c.get(f),cp+"."+f,0);List<String> texts=new ArrayList<>();for(int j=0;j<values.size();j++)texts.add(text(values.get(j),cp+"."+f+"["+j+"]",true));unique(texts,cp+"."+f);}}
    }

    private static List<String> placeholders(String s,String p){Matcher m=PLACEHOLDER.matcher(s);List<String> found=new ArrayList<>();while(m.find()){String a=m.group(1);if(!ALIAS.matcher(a).matches())fail(p,"invalid logical-key placeholder");found.add(a);}String residue=PLACEHOLDER.matcher(s).replaceAll("");if(residue.contains("{{")||residue.contains("}}"))fail(p,"malformed logical-key placeholder");return found;}
    private static void placeholder(JsonNode n,String p){String s=text(n,p,true);List<String> found=placeholders(s,p);if(found.size()!=1||!s.equals("{{"+found.get(0)+"}}"))fail(p,"must be exactly one logical-key placeholder");}

    private static JsonNode obj(JsonNode n,String p,Set<String> required,Set<String> optional){if(n==null||!n.isObject())fail(p,"must be object");Set<String> names=fields(n),allowed=new HashSet<>(required);allowed.addAll(optional);if(!names.containsAll(required))fail(p,"missing required field");if(!allowed.containsAll(names))fail(p,"contains unknown field");return n;}
    private static JsonNode map(JsonNode n,String p){if(n==null||!n.isObject())fail(p,"must be object");Iterator<String> i=n.fieldNames();while(i.hasNext())pattern(MAPPER.getNodeFactory().textNode(i.next()),ALIAS,p+" key");return n;}
    private static JsonNode array(JsonNode n,String p,int min){if(n==null||!n.isArray()||n.size()<min)fail(p,"must be array with required item count");return n;}
    private static String text(JsonNode n,String p,boolean nonempty){if(n==null||!n.isTextual()||(nonempty&&n.asText().isBlank()))fail(p,"must be string");String s=n.asText();if(s.codePoints().anyMatch(c->c<32))fail(p,"must not contain control characters");return s;}
    private static int integer(JsonNode n,String p,int min){if(n==null||!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min)fail(p,"must be integer in range");return n.intValue();}
    private static String en(JsonNode n,Set<String> allowed,String p){String s=text(n,p,true);if(!allowed.contains(s))fail(p,"unknown enum value");return s;}
    private static String pattern(JsonNode n,Pattern regex,String p){String s=text(n,p,true);if(!regex.matcher(s).matches())fail(p,"invalid format");return s;}
    private static BigDecimal money(JsonNode n,String p,boolean zero){String s=text(n,p,true);if(!MONEY.matcher(s).matches())fail(p,"must be decimal string without exponent");BigDecimal v;try{v=new BigDecimal(s);}catch(NumberFormatException e){fail(p,"invalid decimal");return BigDecimal.ZERO;}if(v.signum()<0||(!zero&&v.signum()==0))fail(p,"must be non-negative decimal");return v;}
    private static void positiveId(JsonNode n,String p){String s=text(n,p,true);if(!s.matches("[0-9]+")||s.equals("0")||s.startsWith("0"))fail(p,"must be positive decimal ID string");}
    private static void unique(List<String> v,String p){if(new HashSet<>(v).size()!=v.size())fail(p,"contains duplicate");}
    private static Set<String> fields(JsonNode n){Set<String>s=new HashSet<>();Iterator<String>i=n.fieldNames();while(i.hasNext())s.add(i.next());return s;}
    private static Set<String> set(String... v){return Set.of(v);}
    private static void fail(String p,String m){throw new IllegalArgumentException(p+": "+m);}
}
