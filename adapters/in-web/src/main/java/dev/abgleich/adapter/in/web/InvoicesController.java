package dev.abgleich.adapter.in.web;

import dev.abgleich.application.port.in.CancelInvoiceUseCase;
import dev.abgleich.application.port.in.DecisionResult;
import dev.abgleich.application.port.in.InvoiceQuery;
import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.port.out.DuplicateInvoiceException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.InvalidReferenceException;
import dev.abgleich.domain.reference.PaymentReference;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Invoices by status, their allocation history, registration and cancellation. */
@Controller
class InvoicesController {

    static final int PAGE_SIZE = 200;

    private final InvoiceQuery invoices;
    private final RegisterInvoiceUseCase register;
    private final CancelInvoiceUseCase cancel;
    private final Clock clock;

    InvoicesController(InvoiceQuery invoices, RegisterInvoiceUseCase register, CancelInvoiceUseCase cancel, Clock clock) {
        this.invoices = invoices;
        this.register = register;
        this.cancel = cancel;
        this.clock = clock;
    }

    @ModelAttribute("statuses")
    List<InvoiceStatus> statuses() {
        return List.of(InvoiceStatus.values());
    }

    @GetMapping("/invoices")
    String list(@RequestParam(required = false) InvoiceStatus status, Model model) {
        model.addAttribute("selected", status);
        model.addAttribute("invoices", invoices.list(status, PAGE_SIZE));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", InvoiceForm.empty(LocalDate.now(clock)));
        }
        return "invoices";
    }

    @GetMapping("/invoices/{id}")
    String detail(@PathVariable UUID id, Model model, HttpServletResponse response) {
        return invoices.detail(id)
                .map(detail -> {
                    model.addAttribute("detail", detail);
                    return "invoice";
                })
                .orElseGet(() -> {
                    response.setStatus(HttpStatus.NOT_FOUND.value());
                    model.addAttribute("selected", null);
                    model.addAttribute("invoices", invoices.list(null, PAGE_SIZE));
                    model.addAttribute("form", InvoiceForm.empty(LocalDate.now(clock)));
                    model.addAttribute("error", "This invoice does not exist.");
                    return "invoices";
                });
    }

    /** Raw form fields become value objects here; invalid input never reaches the use case (B04, B05). */
    @PostMapping("/invoices")
    String register(@ModelAttribute("form") InvoiceForm form, Model model, HttpServletResponse response) {
        try {
            UUID id = register.register(form.toCommand());
            return "redirect:/invoices/" + id;
        } catch (DuplicateInvoiceException e) {
            response.setStatus(HttpStatus.CONFLICT.value());
            model.addAttribute("error", e.getMessage() + ".");
        } catch (InvalidInvoiceException | InvalidReferenceException | IllegalArgumentException | DateTimeParseException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("selected", null);
        model.addAttribute("invoices", invoices.list(null, PAGE_SIZE));
        return "invoices";
    }

    @PostMapping("/invoices/{id}/cancel")
    String cancel(@PathVariable UUID id, @RequestParam long version, RedirectAttributes redirect) {
        DecisionResult result = cancel.cancel(id, version);
        redirect.addFlashAttribute("decision", result);
        redirect.addFlashAttribute("decisionClass", Decisions.alertClass(result));
        return "redirect:/invoices/" + id;
    }

    /** What the registration form posts; every field is text until it is validated. */
    public record InvoiceForm(String invoiceNumber, String creditorIban, String debtorName, String amount, String currency,
            String reference, String dueDate) {

        static InvoiceForm empty(LocalDate today) {
            return new InvoiceForm("", "", "", "", "CHF", "", today.plusDays(30).toString());
        }

        RegisterInvoiceCommand toCommand() {
            return new RegisterInvoiceCommand(
                    InvoiceNumber.of(required(invoiceNumber, "Invoice number")),
                    Iban.of(required(creditorIban, "Creditor IBAN")),
                    required(debtorName, "Debtor name"),
                    Money.of(required(amount, "Amount"), Currency.getInstance(required(currency, "Currency")
                            .toUpperCase(Locale.ROOT))),
                    PaymentReference.parse(reference),
                    LocalDate.parse(required(dueDate, "Due date")));
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required.");
            }
            return value.strip();
        }
    }
}
