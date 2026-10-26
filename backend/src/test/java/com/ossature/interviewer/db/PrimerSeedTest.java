package com.ossature.interviewer.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V12 seeds eight questions with vetted reference answers. These checks keep the data usable by the rest of
 * the system: the review node compares the candidate's diagram with the reference, so a dangling edge or an
 * unknown shape would quietly corrupt that comparison, and the reference is truncated at fixed sizes when it
 * is sent to the model.
 */
@EnabledIf("com.ossature.interviewer.db.ScratchDatabase#available")
class PrimerSeedTest {

    static final int REVIEW_LIMIT = 6000;   // agent/app/graph/nodes/review.py truncates the reference here
    static final int SUMMARY_LIMIT = 3000;  // ... and agent/app/graph/nodes/interview.py the summary here
    static final Set<String> SHAPES = Set.of("component", "datastore", "client");

    static ScratchDatabase db;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void setUp() {
        db = ScratchDatabase.create();
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private record Seeded(String slug, String title, String prompt, String source, String status, String reference) {}

    private static List<Seeded> primerQuestions() throws Exception {
        List<Seeded> out = new java.util.ArrayList<>();
        try (Connection c = db.owner(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT slug, title, prompt, source, status, reference_solution::text FROM questions "
                             + "WHERE slug LIKE 'primer-%' ORDER BY slug")) {
            while (rs.next()) {
                out.add(new Seeded(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
            }
        }
        return out;
    }

    @Test
    void eightApprovedSeedQuestionsEachWithAReferenceAnswer() throws Exception {
        List<Seeded> qs = primerQuestions();
        assertThat(qs).hasSize(8);
        for (Seeded q : qs) {
            assertThat(q.source()).as(q.slug()).isEqualTo("SEED");
            assertThat(q.status()).as(q.slug()).isEqualTo("APPROVED");
            assertThat(q.title()).as(q.slug()).isNotBlank().hasSizeLessThanOrEqualTo(140);
            assertThat(q.prompt()).as(q.slug()).hasSizeBetween(200, 4000);
            assertThat(q.reference()).as(q.slug()).isNotBlank();
        }
    }

    @Test
    void everyReferenceFitsWhatTheAgentSendsToTheModel() throws Exception {
        for (Seeded q : primerQuestions()) {
            JsonNode ref = JSON.readTree(q.reference());
            assertThat(q.reference().length()).as(q.slug() + " reference size").isLessThan(REVIEW_LIMIT);
            assertThat(ref.get("summary").asString().length()).as(q.slug() + " summary size").isLessThan(SUMMARY_LIMIT);
        }
    }

    @Test
    void diagramsAreWellFormed() throws Exception {
        for (Seeded q : primerQuestions()) {
            JsonNode diagram = JSON.readTree(q.reference()).get("diagram");
            Set<String> ids = new HashSet<>();
            for (JsonNode n : diagram.get("nodes")) {
                assertThat(ids.add(n.get("id").asString())).as(q.slug() + " duplicate node id").isTrue();
                assertThat(n.get("label").asString()).as(q.slug()).isNotBlank();
                assertThat(SHAPES).as(q.slug() + " shape").contains(n.get("shape").asString());
                assertThat(n.get("x").isNumber() && n.get("y").isNumber()).as(q.slug() + " position").isTrue();
            }
            assertThat(ids.size()).as(q.slug() + " node count").isBetween(5, 14);
            Set<String> edgeIds = new HashSet<>();
            for (JsonNode e : diagram.get("edges")) {
                assertThat(edgeIds.add(e.get("id").asString())).as(q.slug() + " duplicate edge id").isTrue();
                assertThat(ids).as(q.slug() + " edge source").contains(e.get("source").asString());
                assertThat(ids).as(q.slug() + " edge target").contains(e.get("target").asString());
            }
            assertThat(edgeIds).as(q.slug() + " has edges").isNotEmpty();
        }
    }

    @Test
    void everyAnswerHasTradeoffsAndCreditsTheSource() throws Exception {
        for (Seeded q : primerQuestions()) {
            JsonNode ref = JSON.readTree(q.reference());
            assertThat(ref.get("tradeoffs").size()).as(q.slug() + " tradeoffs").isGreaterThanOrEqualTo(4);
            JsonNode a = ref.get("attribution");
            assertThat(a.get("author").asString()).isEqualTo("Donne Martin");
            assertThat(a.get("license").asString()).isEqualTo("CC BY 4.0");
            assertThat(a.get("url").asString()).isEqualTo("https://github.com/donnemartin/system-design-primer");
        }
    }

    @Test
    void rowLevelSecurityIsStillForcedOnQuestionsAfterTheSeed() throws Exception {
        try (Connection c = db.owner(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'questions'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).as("row security enabled").isTrue();
            assertThat(rs.getBoolean(2)).as("row security forced").isTrue();
        }
    }

    @Test
    void theApprovedQuestionsAreVisibleToTheApplicationRoleWithNoUserContext() throws Exception {
        // Through the application role with no user context, as an anonymous visitor: approved rows are public.
        try (Connection c = db.app(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM questions WHERE slug LIKE 'primer-%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(8);
        }
    }
}
