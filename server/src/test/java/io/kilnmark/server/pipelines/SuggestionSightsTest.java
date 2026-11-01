package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.server.quality.ProfileFacts;
import io.kilnmark.server.quality.QualityStore;
import io.kilnmark.server.quality.Sights;

/**
 * AI pipeline suggestions never show a model a marked column's values, even for a designer whose
 * token holds the unmask grant (ADR 0027): the catalogue they read is opened after the caller's
 * sights are told the request answers a model.
 */
class SuggestionSightsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PAYROLL = "urn:kilnmark:hr:staff.payroll";

    /** A designer whose token holds the unmask grant, with salary marked sensitive. */
    static final class DesignerWithGrant extends Sights {
        boolean model;

        @Override
        public boolean data() {
            return true;
        }

        @Override
        public boolean mayUnmask() {
            return !model;
        }

        @Override
        public Set<String> marks(String urn) {
            return Set.of("salary");
        }

        @Override
        public void speakingToModel() {
            model = true;
        }
    }

    static ProfileFacts.FieldFacts field(String name, int position) {
        ObjectNode stats = JSON.createObjectNode();
        stats.put("min", 1);
        stats.put("max", 99000);
        stats.put("null_share", 0.0);
        stats.putArray("top").addObject().put("value", "54000").put("count", 3);
        return new ProfileFacts.FieldFacts(name, position, "float64", "numeric", true, stats, Set.of());
    }

    static PredictionCatalogue stored() {
        ProfileFacts facts = new ProfileFacts(1L, PAYROLL, Instant.now(), 10L, true, false, List.of(),
            List.of(field("id", 1), field("salary", 2)));
        PredictionCatalogue catalogue = new PredictionCatalogue() {
            @Override
            Optional<ProfileFacts> latest(String urn) {
                return Optional.of(facts);
            }
        };
        catalogue.store = new QualityStore(null, JSON) {
            @Override
            public Set<String> sensitive(String urn) {
                return QualityStore.marks(List.of("SALARY")); // compared ignoring case, as stored marks are
            }
        };
        return catalogue;
    }

    @Test
    void theModelIsShownTheMaskedViewEvenWithTheGrant() {
        ProfileFacts asked = PipelineService.suggestionCatalogue(stored(), true, new DesignerWithGrant()).facts(PAYROLL);
        ProfileFacts.FieldFacts salary = asked.field("salary");
        for (String stat : List.of("top", "min", "max")) {
            assertFalse(salary.stats().has(stat), stat + " of a marked column reached the model: " + salary.stats());
        }
        assertTrue(salary.stats().has("null_share"), "shares carry no values and stay");
        assertTrue(asked.field("id").stats().has("top"), "an unmarked column keeps its statistics");
    }

    @Test
    void withoutTheModelTheGrantStillUnmasks() {
        ProfileFacts own = PipelineService.suggestionCatalogue(stored(), false, new DesignerWithGrant()).facts(PAYROLL);
        assertTrue(own.field("salary").stats().has("top"), "the designer's own suggestions use the values");
    }

    @Test
    void withNoCallerTheCatalogueIsMasked() {
        ProfileFacts server = stored().open().facts(PAYROLL);
        assertFalse(server.field("salary").stats().has("top"), "no sights is the masked view: " + server.field("salary"));
        assertTrue(server.field("id").stats().has("top"));
    }

    @Test
    void aModelIsNeverAskedWithoutTheCallersSights() {
        assertThrows(IllegalStateException.class, () -> PipelineService.suggestionCatalogue(stored(), true, null));
        ProfileFacts own = PipelineService.suggestionCatalogue(stored(), false, null).facts(PAYROLL);
        assertFalse(own.field("salary").stats().has("top"));
    }
}
