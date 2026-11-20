package com.grocery.notification.email;

import com.grocery.notification.email.EmailRenderer.RenderedEmail;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 12 §5: the text and HTML parts of an email, absolute links, and that nobody's text can become markup. */
class EmailRendererTest {

    private final EmailRenderer renderer = renderer("https://shop.grocery.test///");

    @Test
    void textAndHtmlCarryTheSameTitleBodyAndAbsoluteLink() {
        RenderedEmail email = renderer.render("ORDER_CONFIRMED", "Order GA-7K3F9Q2M confirmed",
                "Paid ₹245.00. Green Basket will start preparing it soon.", "/orders/0191-abc");

        assertThat(email.subject()).isEqualTo("Order GA-7K3F9Q2M confirmed");
        assertThat(email.url()).isEqualTo("https://shop.grocery.test/orders/0191-abc");
        assertThat(email.text()).isEqualTo("""
                Order GA-7K3F9Q2M confirmed

                Paid ₹245.00. Green Basket will start preparing it soon.

                Open in Grocery: https://shop.grocery.test/orders/0191-abc

                --\s
                Grocery
                You get this email because email for order updates is on. You can turn it off in your \
                notification settings in Grocery.
                """);
        assertThat(email.html())
                .startsWith("<!DOCTYPE html>")
                .contains("<title>Order GA-7K3F9Q2M confirmed</title>")
                .contains(">Order GA-7K3F9Q2M confirmed</h1>")
                .contains(">Paid ₹245.00. Green Basket will start preparing it soon.</p>")
                .contains("<a href=\"https://shop.grocery.test/orders/0191-abc\"")
                .contains(">Open in Grocery</a>")
                .contains("email for order updates is on");
    }

    @Test
    void reviewAndReturnTextCanNeverInjectHtml() {
        String title = "New 5★ review <img src=x onerror=alert(1)>";
        String body = "Great \"rice\" & <script>alert('x')</script>\n<a href='https://evil.test'>click</a>";
        RenderedEmail email = renderer.render("RETURN_REJECTED", title, body, "/orders/1?a=1&b=\"2\"");

        assertThat(email.html())
                .doesNotContain("<img")
                .doesNotContain("<script")
                .doesNotContain("<a href='https://evil.test'>")
                .contains("New 5★ review &lt;img src=x onerror=alert(1)&gt;")
                .contains("Great &quot;rice&quot; &amp; &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;\n"
                        + "&lt;a href=&#39;https://evil.test&#39;&gt;click&lt;/a&gt;")
                // the link is an attribute: escaped too, it cannot leave the href
                .contains("href=\"https://shop.grocery.test/orders/1?a=1&amp;b=&quot;2&quot;\"");
        // the text part is plain text: shown as it is
        assertThat(email.text()).contains(body).contains(title);
    }

    @Test
    void aNotificationWithoutLinkHasNoButtonAndTheFooterNamesItsCategory() {
        RenderedEmail suspended = renderer.render("ACCOUNT_SUSPENDED", "Your account is suspended", "Fraud check",
                null);
        assertThat(suspended.url()).isNull();
        assertThat(suspended.text()).doesNotContain("Open in Grocery").contains("email for account updates is on");
        assertThat(suspended.html()).doesNotContain("<a ").contains("email for account updates is on");

        RenderedEmail approval = renderer.render("AI_APPROVAL_REQUESTED", "Approval needed: x", "", "assistant/x");
        assertThat(approval.url()).isEqualTo("https://shop.grocery.test/assistant/x");
        assertThat(approval.text()).contains("email for approval requests is on").doesNotContain("\n\n\n");
        assertThat(approval.html()).doesNotContain("white-space:pre-line");     // no empty body paragraph

        assertThat(renderer.render("SOMETHING_OLD", "T", "B", null).text()).contains("order updates");
    }

    @Test
    void theSubjectIsOneLineAndHtmlEscapingIsComplete() {
        assertThat(renderer.render("ORDER_CONFIRMED", "Line one\r\nBcc: x@evil.test", "", null).subject())
                .isEqualTo("Line one  Bcc: x@evil.test");
        assertThat(Html.escape("<>&\"'")).isEqualTo("&lt;&gt;&amp;&quot;&#39;");
        assertThat(Html.escape("a\u0000b\tc\nd")).isEqualTo("ab\tc\nd");
        assertThat(Html.escape(null)).isEmpty();
        assertThat(renderer("http://localhost:6420").absolute("/orders/1")).isEqualTo("http://localhost:6420/orders/1");
        assertThat(renderer.absolute(" ")).isNull();
    }

    private static EmailRenderer renderer(String baseUrl) {
        EmailProperties properties = new EmailProperties();
        properties.setAppBaseUrl(baseUrl);
        return new EmailRenderer(properties);
    }
}
