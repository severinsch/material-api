import configure.constraintMessages
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Migrations of existing data, on a separate database in the test container. */
class MigrationTest {

    private val url = TestDatabase.url("migration_test")

    private val tenantTables = listOf(
        "storage_locations", "materials", "individual_items", "bulk_stock", "purchases", "damage_reports", "kits",
        "kit_groups", "kit_group_options", "kit_group_amounts", "loans", "loan_lines",
    )

    @BeforeTest
    fun createDatabase() {
        TestDatabase.execute("DROP DATABASE IF EXISTS migration_test")
        TestDatabase.execute("CREATE DATABASE migration_test")
    }

    @Test
    fun `existing data is assigned to a default tenant without changing it`() {
        TestDatabase.migrate(url, target = "1")
        val (location, bulk, individual, item, purchase) = List(5) { Uuid.random() }
        val (damage, kit, group, loan) = List(4) { Uuid.random() }
        val past = "'2020-01-01 00:00:00+00'"
        TestDatabase.execute(
            """
            INSERT INTO storage_locations (id, name, updated_at) VALUES ('$location', 'Keller', $past);
            INSERT INTO materials (id, name, tracking_type, updated_at)
            VALUES ('$bulk', 'Hering', 'BULK', $past), ('$individual', 'Kohtenblatt', 'INDIVIDUAL', $past);
            INSERT INTO bulk_stock (material_id, quantity_available, storage_location_id, updated_at)
            VALUES ('$bulk', 10, '$location', $past);
            INSERT INTO individual_items (id, material_id, identifier, storage_location_id, updated_at)
            VALUES ('$item', '$individual', '1', '$location', $past);
            INSERT INTO purchases (id, material_id, quantity, updated_at) VALUES ('$purchase', '$bulk', 5, $past);
            INSERT INTO damage_reports (id, individual_item_id, description, updated_at)
            VALUES ('$damage', '$item', 'Riss', $past);
            INSERT INTO kits (id, name, updated_at) VALUES ('$kit', 'Jurte', $past);
            INSERT INTO kit_groups (id, kit_id, position, required_units, updated_at) VALUES ('$group', '$kit', 0, 1, $past);
            INSERT INTO kit_group_options (group_id, material_id) VALUES ('$group', '$individual');
            INSERT INTO loans (id, borrower, lent_at, updated_at) VALUES ('$loan', 'Stamm Nord', '2020-01-01', $past);
            INSERT INTO loan_lines (id, loan_id, individual_item_id, material_id, quantity)
            VALUES ('${Uuid.random()}', '$loan', '$item', NULL, NULL), ('${Uuid.random()}', '$loan', NULL, '$bulk', 2);
            """,
            url
        )

        TestDatabase.migrate(url)

        val (tenant, name, slug) = TestDatabase.query("SELECT id, name, slug FROM tenants", url).single()
        assertEquals("Standard" to "standard", name to slug)
        for (table in tenantTables) {
            val (rows, assigned) = TestDatabase.query(
                "SELECT count(*), count(*) FILTER (WHERE tenant_id = '$tenant') FROM $table", url
            ).single()
            assertTrue(rows as Long > 0, table)
            assertEquals(rows, assigned, table)
        }
        val changed = TestDatabase.query(
            tenantTables.filter { it !in listOf("kit_group_options", "kit_group_amounts", "loan_lines") }
                .joinToString(" UNION ALL ") { "SELECT '$it' FROM $it WHERE updated_at <> $past" },
            url
        )
        assertEquals(emptyList(), changed, "tables with changed updated_at")
        // Deleting a location only nulls the reference, not tenant_id (part of the composite foreign key).
        TestDatabase.execute("DELETE FROM storage_locations", url)
        assertEquals(listOf(listOf(tenant)), TestDatabase.query("SELECT tenant_id FROM bulk_stock", url))
    }

    @Test
    fun `groups get their amount, optional groups an amount of 0 as default`() {
        TestDatabase.migrate(url, target = "3")
        val (north, south, jurte, kohte) = List(4) { Uuid.random() }
        val (pegs, windows, poles) = List(3) { Uuid.random() }
        TestDatabase.execute(
            """
            INSERT INTO tenants (id, name, slug)
            VALUES ('$north', 'Stamm Nord', 'nord'), ('$south', 'Stamm Süd', 'sued');
            INSERT INTO kits (id, name, tenant_id) VALUES ('$jurte', 'Jurte', '$north'), ('$kohte', 'Kohte', '$south');
            INSERT INTO kit_groups (id, kit_id, position, required_units, is_optional, tenant_id)
            VALUES ('$pegs', '$jurte', 0, 30, FALSE, '$north'), ('$windows', '$jurte', 1, 12, TRUE, '$north'),
                   ('$poles', '$kohte', 0, 2, FALSE, '$south');
            """,
            url
        )

        TestDatabase.migrate(url)

        val amounts = TestDatabase.query(
            "SELECT group_id, position, label, required_units, tenant_id FROM kit_group_amounts", url
        ).map { row -> row.map { it?.toString() } }.toSet()
        assertEquals(
            setOf(
                listOf("$pegs", "0", null, "30", "$north"),
                listOf("$windows", "0", "ohne", "0", "$north"),
                listOf("$windows", "1", "mit", "12", "$north"),
                listOf("$poles", "0", null, "2", "$south"),
            ),
            amounts
        )
        val oldColumns = TestDatabase.query(
            "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'kit_groups' AND column_name IN ('required_units', 'is_optional')",
            url
        )
        assertEquals(emptyList(), oldColumns)
    }

    @Test
    fun `an empty database gets no tenant`() {
        TestDatabase.migrate(url)
        assertEquals(emptyList(), TestDatabase.query("SELECT * FROM tenants", url))
    }

    @Test
    fun `the constraints with specific error messages exist`() {
        TestDatabase.migrate(url)
        val names = TestDatabase.query(
            "SELECT conname FROM pg_constraint UNION SELECT relname FROM pg_class WHERE relkind = 'i'", url
        ).map { it.single() }.toSet()
        assertEquals(emptySet(), constraintMessages.keys - names)
    }
}
