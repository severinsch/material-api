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
        "storage_locations", "materials", "material_variants", "individual_items", "bulk_stock", "purchases",
        "damage_reports", "kits", "kit_groups", "kit_group_options", "kit_group_amounts", "loans", "loan_lines",
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
    fun `every material gets one unnamed variant that takes over its stock, items, purchases and loans`() {
        TestDatabase.migrate(url, target = "4")
        val (north, south, location) = List(3) { Uuid.random() }
        val (bulk, individual, southBulk) = List(3) { Uuid.random() }
        val (kit, group, loan, southLoan) = List(4) { Uuid.random() }
        val (item, purchase) = List(2) { Uuid.random() }
        val past = "'2020-01-01 00:00:00+00'"
        TestDatabase.execute(
            """
            INSERT INTO tenants (id, name, slug) VALUES ('$north', 'Stamm Nord', 'nord'), ('$south', 'Stamm Süd', 'sued');
            INSERT INTO storage_locations (id, name, tenant_id) VALUES ('$location', 'Keller', '$north');
            INSERT INTO materials (id, name, tracking_type, tenant_id, updated_at)
            VALUES ('$bulk', 'Hering', 'BULK', '$north', $past), ('$individual', 'Kohtenblatt', 'INDIVIDUAL', '$north', $past),
                   ('$southBulk', 'Hering', 'BULK', '$south', $past);
            INSERT INTO bulk_stock (material_id, quantity_available, quantity_damaged, storage_location_id, location_detail,
                                    tenant_id, updated_at)
            VALUES ('$bulk', 10, 2, '$location', 'Regal 1', '$north', $past), ('$southBulk', 5, 0, NULL, NULL, '$south', $past);
            INSERT INTO individual_items (id, material_id, identifier, tenant_id, updated_at)
            VALUES ('$item', '$individual', 'K01', '$north', $past), ('${Uuid.random()}', '$individual', 'K02', '$north', $past);
            INSERT INTO purchases (id, material_id, quantity, tenant_id, updated_at)
            VALUES ('$purchase', '$bulk', 5, '$north', $past), ('${Uuid.random()}', '$southBulk', 3, '$south', $past);
            INSERT INTO kits (id, name, tenant_id) VALUES ('$kit', 'Jurte', '$north');
            INSERT INTO kit_groups (id, kit_id, position, tenant_id) VALUES ('$group', '$kit', 0, '$north');
            INSERT INTO kit_group_options (group_id, material_id, tenant_id)
            VALUES ('$group', '$individual', '$north'), ('$group', '$bulk', '$north');
            INSERT INTO loans (id, borrower, tenant_id) VALUES ('$loan', 'Stamm Ost', '$north'), ('$southLoan', 'Stamm Ost', '$south');
            INSERT INTO loan_lines (id, loan_id, individual_item_id, material_id, quantity, tenant_id)
            VALUES ('${Uuid.random()}', '$loan', '$item', NULL, NULL, '$north'),
                   ('${Uuid.random()}', '$loan', NULL, '$bulk', 4, '$north'),
                   ('${Uuid.random()}', '$southLoan', NULL, '$southBulk', 2, '$south');
            """,
            url
        )

        TestDatabase.migrate(url)

        // One unnamed variant per material, with the material's tenant and type, and an id of its own.
        val variants = TestDatabase.query(
            "SELECT material_id, tenant_id, tracking_type, name, id = material_id FROM material_variants", url
        ).map { row -> row.map { it?.toString() } }.toSet()
        assertEquals(
            setOf(
                listOf("$bulk", "$north", "BULK", null, "false"),
                listOf("$individual", "$north", "INDIVIDUAL", null, "false"),
                listOf("$southBulk", "$south", "BULK", null, "false"),
            ),
            variants
        )
        // Every row kept its material (now through its variant), its tenant and its data.
        val rows = TestDatabase.query(
            """
            SELECT 'stock', v.material_id, b.tenant_id,
                   format('%s/%s/%s/%s', b.quantity_available, b.quantity_damaged, b.storage_location_id, b.location_detail)
            FROM bulk_stock b JOIN material_variants v ON v.id = b.variant_id
            UNION ALL
            SELECT 'item', v.material_id, i.tenant_id, i.identifier
            FROM individual_items i JOIN material_variants v ON v.id = i.variant_id
            UNION ALL
            SELECT 'purchase', v.material_id, p.tenant_id, p.quantity::text
            FROM purchases p JOIN material_variants v ON v.id = p.variant_id
            UNION ALL
            SELECT 'loan', coalesce(v.material_id, i.id), l.tenant_id, coalesce(l.quantity::text, 'item')
            FROM loan_lines l LEFT JOIN material_variants v ON v.id = l.variant_id
                              LEFT JOIN individual_items i ON i.id = l.individual_item_id
            UNION ALL
            SELECT 'option', o.material_id, o.tenant_id, coalesce(o.variant_id::text, 'any variant')
            FROM kit_group_options o
            """,
            url
        ).map { row -> row.map { it?.toString() } }.toSet()
        assertEquals(
            setOf(
                listOf("stock", "$bulk", "$north", "10/2/$location/Regal 1"),
                listOf("stock", "$southBulk", "$south", "5/0//"),
                listOf("item", "$individual", "$north", "K01"),
                listOf("item", "$individual", "$north", "K02"),
                listOf("purchase", "$bulk", "$north", "5"),
                listOf("purchase", "$southBulk", "$south", "3"),
                listOf("loan", "$item", "$north", "item"),
                listOf("loan", "$bulk", "$north", "4"),
                listOf("loan", "$southBulk", "$south", "2"),
                listOf("option", "$individual", "$north", "any variant"),
                listOf("option", "$bulk", "$north", "any variant"),
            ),
            rows
        )
        val changed = TestDatabase.query(
            listOf("materials", "material_variants", "bulk_stock", "individual_items", "purchases")
                .joinToString(" UNION ALL ") { "SELECT '$it' FROM $it WHERE updated_at <> $past" },
            url
        )
        assertEquals(emptyList(), changed, "tables with changed updated_at")
        val oldColumns = TestDatabase.query(
            "SELECT table_name FROM information_schema.columns WHERE column_name = 'material_id' " +
                "AND table_name IN ('bulk_stock', 'individual_items', 'purchases', 'loan_lines')",
            url
        )
        assertEquals(emptyList(), oldColumns)
        // Deleting a location still only nulls the reference.
        TestDatabase.execute("DELETE FROM storage_locations", url)
        assertEquals(
            setOf(listOf("$north", null), listOf("$south", null)),
            TestDatabase.query("SELECT tenant_id, storage_location_id FROM bulk_stock", url)
                .map { row -> row.map { it?.toString() } }.toSet()
        )
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
