import api.*
import configure.ApiJson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.SQLException
import kotlin.test.*
import kotlin.uuid.Uuid

/** Tenants only see and reference their own data, enforced by row-level security and composite foreign keys. */
class TenantIsolationTest {

    @Test
    fun `another tenant's data is invisible and can't be changed`() = apiTest { context ->
        val location = createLocation()
        val bulk = createBulk(location = location)
        val individual = createIndividual()
        val item = createItem(individual, "1")
        val purchase: Purchase = post("purchases") { setBody(NewPurchase(bulk.id, 5)) }.expect(HttpStatusCode.Created)
        val damage: DamageReport = post("damages") { setBody(NewDamageReport(item.id, "Riss")) }
            .expect(HttpStatusCode.Created)
        val kit: Kit = post("kits") { setBody(kitOf(bulk.id)) }.expect(HttpStatusCode.Created)
        val loan: Loan = post("loans") { setBody(loanOf(itemIds = listOf(item.id))) }.expect(HttpStatusCode.Created)

        // The other tenant's bodies reference its own data, so a PUT fails only because the id is unknown there.
        val other = context.clientFor(TenantRole.EDITOR, TestDatabase.createTenant("Stamm West"))
        val otherBulk = other.createBulk()
        val otherItem = other.createItem(other.createIndividual(), "1")
        val resources = listOf(
            Triple("locations", location.id, ApiJson.encodeToString(NewStorageLocation("Garage"))),
            Triple("materials", bulk.id, ApiJson.encodeToString<NewMaterial>(NewBulkMaterial("Hering", availableCount = 1))),
            Triple("items", item.id, ApiJson.encodeToString(NewIndividualItem(otherItem.materialId, "1", ItemCondition.GOOD))),
            Triple("purchases", purchase.id, ApiJson.encodeToString(NewPurchase(otherBulk.id, 1))),
            Triple("damages", damage.id, ApiJson.encodeToString(NewDamageReport(otherItem.id, "Riss"))),
            Triple("kits", kit.id, ApiJson.encodeToString(kitOf(otherBulk.id))),
            Triple("loans", loan.id, ApiJson.encodeToString(loanOf(itemIds = listOf(otherItem.id)))),
        )
        for ((path, id, body) in resources) {
            val list: List<JsonObject> = other.get(path).expect(HttpStatusCode.OK)
            assertTrue(list.none { it["id"]?.jsonPrimitive?.content == id.toString() }, path)
            other.get("$path/$id").expectError(HttpStatusCode.NotFound)
            other.put("$path/$id") { setBody(body) }.expectError(HttpStatusCode.NotFound)
            other.delete("$path/$id").expectError(HttpStatusCode.NotFound)
            // Still there for its own tenant.
            assertEquals(HttpStatusCode.OK, get("$path/$id").status, path)
        }

        // Names are unique per tenant only (both have a "Hering"), and aggregates only count the tenant's own data.
        assertEquals(0, other.getMaterial(otherBulk.id).loanedCount)
        other.post("loans") { setBody(loanOf(materials = listOf(LoanQuantity(otherBulk.id, 3)))) }
            .expect<Loan>(HttpStatusCode.Created)
        assertEquals(0, getMaterial(bulk.id).loanedCount)
    }

    @Test
    fun `references to another tenant's data are rejected`() = apiTest { context ->
        val location = createLocation()
        val bulk = createBulk()
        val individual = createIndividual()
        val item = createItem(individual, "1")

        val other = context.clientFor(TenantRole.EDITOR, TestDatabase.createTenant("Stamm West"))
        val otherIndividual = other.createIndividual()
        val requests = listOf(
            other.post("materials") {
                setBody<NewMaterial>(NewBulkMaterial("Hering", availableCount = 1, storageLocationId = location.id))
            },
            other.post("items") { setBody(NewIndividualItem(individual.id, "1", ItemCondition.GOOD)) },
            other.post("items") {
                setBody(NewIndividualItem(otherIndividual.id, "1", ItemCondition.GOOD, storageLocationId = location.id))
            },
            other.post("purchases") { setBody(NewPurchase(bulk.id, 1)) },
            other.post("damages") { setBody(NewDamageReport(item.id, "Riss")) },
            other.post("kits") { setBody(kitOf(bulk.id)) },
            other.post("loans") { setBody(loanOf(itemIds = listOf(item.id))) },
            other.post("loans") { setBody(loanOf(materials = listOf(LoanQuantity(bulk.id, 1)))) },
        )
        requests.forEach { assertEquals(HttpStatusCode.BadRequest, it.status, it.bodyAsText()) }
    }

    @Test
    fun `unknown tenants are not found`() = apiTest {
        get("/tenants/${Uuid.random()}/materials").expectError(HttpStatusCode.NotFound)
        get("/tenants/not-a-uuid/materials").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `composite foreign keys reject references across tenants`() = apiTest {
        val material = createIndividual()
        val item = createItem(material, "1")
        val location = createLocation()

        // Rows of another tenant, inserted as table owner: only the foreign keys stand in the way, not the policies.
        val other = TestDatabase.createTenant("Stamm West")
        val (loan, kit, group, bulk) = List(4) { Uuid.random() }
        TestDatabase.execute(
            """
            INSERT INTO loans (id, borrower, tenant_id) VALUES ('$loan', 'Stamm Ost', '$other');
            INSERT INTO kits (id, name, tenant_id) VALUES ('$kit', 'Jurte', '$other');
            INSERT INTO kit_groups (id, kit_id, position, required_units, tenant_id) VALUES ('$group', '$kit', 0, 1, '$other');
            INSERT INTO materials (id, name, tracking_type, tenant_id) VALUES ('$bulk', 'Hering', 'BULK', '$other');
            """
        )
        val crossTenant = listOf(
            "INSERT INTO individual_items (id, material_id, identifier, tenant_id) " +
                "VALUES ('${Uuid.random()}', '${material.id}', '2', '$other')",
            "INSERT INTO loan_lines (id, loan_id, individual_item_id, tenant_id) " +
                "VALUES ('${Uuid.random()}', '$loan', '${item.id}', '$other')",
            "INSERT INTO kit_group_options (group_id, material_id, tenant_id) VALUES ('$group', '${material.id}', '$other')",
            "INSERT INTO bulk_stock (material_id, storage_location_id, tenant_id) " +
                "VALUES ('$bulk', '${location.id}', '$other')",
        )
        for (insert in crossTenant) {
            val e = assertFailsWith<SQLException>(insert) { TestDatabase.execute(insert) }
            assertEquals("23503", e.sqlState, e.message)
        }
    }

    @Test
    fun `a transaction without tenant sees no rows`() = apiTest {
        createBulk()
        val counts = TestDatabase.connect { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                val asOwner = it.executeQuery("SELECT count(*) FROM materials").use { rs -> rs.next(); rs.getInt(1) }
                it.execute("SET LOCAL ROLE material_app")
                val asApp = it.executeQuery("SELECT count(*) FROM materials").use { rs -> rs.next(); rs.getInt(1) }
                connection.rollback()
                asOwner to asApp
            }
        }
        assertEquals(1 to 0, counts)
    }

    @Test
    fun `the application role is subject to row-level security`() {
        val (superuser, bypassesRls) = TestDatabase.query(
            "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'material_app'"
        ).single()
        assertEquals(false, superuser)
        assertEquals(false, bypassesRls)
    }

    /** The convention for tenant-owned tables in the README, so later migrations can't forget a part of it. */
    @Test
    fun `every tenant-owned table has the tenant default and the policy`() {
        val policy = "(tenant_id = current_tenant_id())"
        val tables = TestDatabase.query(
            """
            SELECT c.relname, c.relrowsecurity, pg_get_expr(d.adbin, d.adrelid),
                   EXISTS (SELECT FROM pg_policies p WHERE p.tablename = c.relname AND p.policyname = 'tenant_isolation'
                                                     AND p.cmd = 'ALL' AND p.qual = '$policy' AND p.with_check = '$policy')
            FROM pg_attribute a
                     JOIN pg_class c ON c.oid = a.attrelid
                     LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attname = 'tenant_id' AND NOT a.attisdropped AND c.relkind = 'r'
              AND c.relnamespace = 'public'::regnamespace
              -- Memberships are read before a tenant is chosen.
              AND c.relname <> 'tenant_members'
            """
        )
        assertEquals(11, tables.size)
        for ((table, rowSecurity, default, hasPolicy) in tables) {
            assertEquals(true, rowSecurity, "row-level security on $table")
            assertEquals("current_tenant_id()", default, "default of $table.tenant_id")
            assertEquals(true, hasPolicy, "policy on $table")
        }

        // A new table without tenant_id would be visible to all tenants: it has to be added here deliberately.
        val withoutTenant = TestDatabase.query(
            """
            SELECT relname FROM pg_class c
            WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace
              AND NOT EXISTS (SELECT FROM pg_attribute WHERE attrelid = c.oid AND attname = 'tenant_id')
            """
        ).map { it.single() }.toSet()
        assertEquals(
            setOf("flyway_schema_history", "tenants", "users", "password_logins", "sessions", "setup_tokens"),
            withoutTenant
        )
    }

    @Test
    fun `the application role can access all application tables`() {
        val privileges = TestDatabase.query(
            """
            SELECT relname, has_table_privilege('material_app', oid, 'SELECT, INSERT, UPDATE, DELETE')
            FROM pg_class
            WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace
            """
        ).associate { (table, granted) -> table to granted }
        assertEquals(false, privileges["flyway_schema_history"])
        val missing = privileges.filter { (table, granted) -> table != "flyway_schema_history" && granted != true }
        assertEquals(emptySet(), missing.keys)
    }

    private fun kitOf(materialId: Uuid) =
        NewKit("Jurte", groups = listOf(NewKitGroup(requiredUnits = 1, options = listOf(NewKitGroupOption(materialId)))))

    private fun loanOf(itemIds: List<Uuid> = emptyList(), materials: List<LoanQuantity> = emptyList()) =
        NewLoan("Stamm Nord", LocalDate(2026, 10, 1), itemIds = itemIds, materials = materials)
}
