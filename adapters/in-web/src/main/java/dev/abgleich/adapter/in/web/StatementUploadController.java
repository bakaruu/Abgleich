package dev.abgleich.adapter.in.web;

import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import dev.abgleich.application.statement.port.in.StatementReportQuery;
import dev.abgleich.domain.statement.InvalidStatementException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * The "Upload statement" screen. With htmx only the result fragment is returned and swapped in;
 * without JavaScript the same form posts normally and gets the whole page.
 */
@Controller
class StatementUploadController {

    static final String VIEW = "upload";
    static final String RESULT_FRAGMENT = "upload :: result";

    private final ProcessStatementUseCase processStatement;
    private final StatementReportQuery reports;

    StatementUploadController(ProcessStatementUseCase processStatement, StatementReportQuery reports) {
        this.processStatement = processStatement;
        this.reports = reports;
    }

    @GetMapping("/")
    String uploadPage() {
        return VIEW;
    }

    @PostMapping(path = "/statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    String upload(@RequestParam(name = "file", required = false) MultipartFile file,
            @RequestHeader(name = "HX-Request", required = false) String htmxRequest,
            Model model, HttpServletResponse response) {
        String view = htmxRequest != null ? RESULT_FRAGMENT : VIEW;
        if (file == null || file.isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("error", "Choose a camt.053 or Norma 43 file to upload.");
            return view;
        }
        try {
            StatementProcessed processed = processStatement.process(
                    new ImportStatementCommand(ImportSource.WEB, file::getInputStream));
            model.addAttribute("results", List.of(UploadView.of(processed, reports, file.getOriginalFilename())));
        } catch (InvalidStatementException rejected) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", rejected.getMessage());
        }
        return view;
    }
}
