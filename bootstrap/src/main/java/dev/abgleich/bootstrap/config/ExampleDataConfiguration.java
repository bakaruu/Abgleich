package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.out.synthetic.SyntheticExampleData;
import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.port.out.ExampleDataPort;
import dev.abgleich.application.service.ExampleDataService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the synthetic example behind "Load example". */
@Configuration(proxyBeanMethods = false)
class ExampleDataConfiguration {

    @Bean
    SyntheticExampleData exampleData() {
        return new SyntheticExampleData();
    }

    @Bean
    ExampleDataUseCase exampleDataUseCase(ExampleDataPort examples, RegisterInvoiceUseCase registerInvoice,
            ProcessStatementUseCase processStatement) {
        return new ExampleDataService(examples, registerInvoice, processStatement);
    }
}
