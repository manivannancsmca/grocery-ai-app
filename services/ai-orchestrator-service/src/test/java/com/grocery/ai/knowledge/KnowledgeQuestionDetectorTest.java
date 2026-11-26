package com.grocery.ai.knowledge;

import com.grocery.ai.knowledge.search.KnowledgeQuestionDetector;
import com.grocery.ai.knowledge.search.KnowledgeQuestionDetector.Route;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic routing of the knowledge base (docs/rag/RAG-MCP-INTEGRATION.md, "Routing"), with the brief's
 * example questions first.
 */
class KnowledgeQuestionDetectorTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            // the brief's examples
            "What is your minimum grocery order amount?            | KNOWLEDGE_AND_LIVE",
            "Do you have organic carrots available?                | NONE",
            "Add 2 kg organic carrots to my cart.                  | NONE",
            "What is your refund policy?                           | KNOWLEDGE",
            "Where is my order?                                    | NONE",
            "Can I cancel my current order?                        | KNOWLEDGE_AND_LIVE",
            "What is the delivery policy?                          | KNOWLEDGE",
            "Find organic rice.                                    | NONE",
            "What is the delivery policy and where is my order?    | KNOWLEDGE_AND_LIVE",
            "Can I return groceries after 30 days?                 | KNOWLEDGE",
            // more policy and how-to questions
            "how much are the delivery charges                     | KNOWLEDGE_AND_LIVE",
            "Is there free delivery?                               | KNOWLEDGE_AND_LIVE",
            "which payment methods do you accept                   | KNOWLEDGE",
            "Do you have cash on delivery?                         | KNOWLEDGE",
            "How do I become a vendor?                             | KNOWLEDGE",
            "vendor onboarding steps                               | KNOWLEDGE",
            "When are payouts made?                                | KNOWLEDGE",
            "How long do bananas stay fresh?                       | KNOWLEDGE",
            "refund kab milega                                     | KNOWLEDGE",
            "Where is my refund?                                   | KNOWLEDGE_AND_LIVE",
            "What happens if the delivery partner is late?         | MAYBE",
            "How do I find paneer under 200?                       | MAYBE",
            // wordings added after the evaluation (docs/rag/RAG-TESTING-GUIDE.md)
            "Above what amount is grocery delivery free?           | KNOWLEDGE_AND_LIVE",
            "Is there a plan with unlimited free deliveries?       | KNOWLEDGE_AND_LIVE",
            "What do the order statuses mean?                      | MAYBE",
            "How many delivery addresses can I save?               | MAYBE",
            // ordinary shopping and order messages
            "show my cart                                          | NONE",
            "Do you have fresh cod fish?                           | NONE",
            "Track GA-7K3F9Q2M                                     | NONE",
            "hello                                                 | NONE",
            "''                                                    | NONE"
    })
    void routes(String message, Route expected) {
        assertThat(KnowledgeQuestionDetector.route(message)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "What is your minimum grocery order amount?            | true",
            "how much are the delivery charges                     | true",
            "Above what amount is grocery delivery free?           | true",
            "Is there free delivery?                               | true",
            "What is your refund policy?                           | false",
            "What is the delivery policy?                          | false",
            "''                                                    | false"
    })
    void recognisesQuestionsAboutTheStoreRules(String message, boolean expected) {
        assertThat(KnowledgeQuestionDetector.aboutStoreRules(message)).isEqualTo(expected);
    }
}
