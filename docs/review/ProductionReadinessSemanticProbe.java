import com.example.report.catalog.*;
import com.example.report.config.AgentProperties;
import com.example.report.semantic.*;
import com.example.report.support.TestCatalog;
import java.util.*;

/** Historical f311fad diagnostic. V2 removes DomainIntentParser; use SemanticV2Test on the new code. */
public class ProductionReadinessSemanticProbe {
    public static void main(String[] args) {
        var data = new TestCatalog();
        var catalog = new ReportCatalogService(data.catalog(), new AgentProperties());
        var planner = new SemanticPlanner(catalog);
        var parser = new DomainIntentParser();
        for (String message : List.of("只查销售报表，不要派单", "查一下销售报表，不要派单")) {
            var state = new DialogueState();
            var context = new IntentParser.Context(state,
                    catalog.dispatchableReports(TestCatalog.USER1).stream().map(CatalogEntry::ref).toList(),
                    planner.mentions(TestCatalog.USER1, message));
            var intent = parser.parse(message, context).orElseThrow();
            new IntentCodec().validate(intent, message);
            planner.merge(TestCatalog.USER1, state, intent);
            planner.requireCoverage(state, intent, context.mentionedReportTerms());
            planner.validate(TestCatalog.USER1, state);
            if (intent.reports().operation() != SemanticIntent.Operation.REMOVE
                    || state.getDesired().reportIds().contains(TestCatalog.SALES))
                throw new AssertionError("Behavior changed: reassess the finding");
            System.out.println(message + " => " + intent.action() + ", reports="
                    + intent.reports().operation() + ", actual scope=" + state.getDesired().reportIds());
        }
    }
}
