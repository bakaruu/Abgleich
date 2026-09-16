package dev.abgleich.adapter.out.synthetic;

import dev.abgleich.application.port.out.ExampleDataPort;

/** The example shown in the UI: always the default seed and day, generated once. */
public final class SyntheticExampleData implements ExampleDataPort {

    private final ExampleData data;

    public SyntheticExampleData() {
        SyntheticDataset dataset = SyntheticDataGenerator.defaultDataset();
        this.data = new ExampleData(dataset.invoices(), dataset.files());
    }

    @Override
    public ExampleData exampleData() {
        return data;
    }
}
