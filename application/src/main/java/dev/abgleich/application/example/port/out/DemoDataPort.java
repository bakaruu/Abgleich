package dev.abgleich.application.example.port.out;

/** Storage operations only the public demo needs. */
public interface DemoDataPort {

    /**
     * Deletes every stored business record: imports, payments, invoices, allocations, events and processed
     * messages. Tables added later are included without changing this port (B43).
     *
     * @throws StorageException if the database fails
     */
    void deleteAllData();

    /** Bytes the stored data occupies, to keep the demo within its disk quota (B44). */
    long storedBytes();
}
