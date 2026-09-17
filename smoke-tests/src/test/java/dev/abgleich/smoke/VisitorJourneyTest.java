package dev.abgleich.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What someone who opens the demo link does in the first two minutes, in a real Chromium: load the example,
 * decide a proposal, look at the summary. Browser console errors fail the test, which also catches anything the
 * strict Content-Security-Policy blocks (B32).
 */
class VisitorJourneyTest {

    private static final String BASE_URL = System.getProperty("smoke.baseUrl", "http://localhost:8080")
            .replaceAll("/+$", "");

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void close() {
        browser.close();
        playwright.close();
    }

    @Test
    void visitor_loads_the_example_decides_a_proposal_and_reads_the_summary() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            List<String> consoleErrors = new ArrayList<>();
            page.onConsoleMessage(message -> {
                if ("error".equals(message.type())) {
                    consoleErrors.add(message.text());
                }
            });

            Response home = page.navigate(BASE_URL + "/");
            assertThat(home.status()).isEqualTo(200);
            assertThat(home.headers().get("content-security-policy")).contains("script-src 'self'");

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load both")).click();
            page.getByText("Example loaded.").waitFor();

            page.navigate(BASE_URL + "/review");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Review")).count())
                    .isPositive();
            Locator confirm = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Confirm").setExact(true));
            // Earlier runs on the same day may have decided every proposal already; the nightly reset brings them back.
            if (confirm.count() > 0) {
                confirm.first().click();
                page.getByText("Confirmed:").first().waitFor();
            }

            page.navigate(BASE_URL + "/summary");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Summary")).isVisible()).isTrue();
            assertThat(page.locator(".summary-figures").innerText()).contains("Settled automatically").contains("%");

            assertThat(consoleErrors).as("browser console errors, including CSP violations").isEmpty();
        }
    }
}
