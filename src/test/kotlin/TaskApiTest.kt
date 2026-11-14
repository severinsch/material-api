import api.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.test.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

class TaskApiTest {

    private val today = Clock.System.todayIn(TimeZone.of("Europe/Berlin"))

    @Test
    fun `tasks are created, changed and deleted with their links`() = apiTest { context ->
        val bulk = createBulk()
        val kohte = createIndividual("Kohte")
        val item = createItem(kohte, "K01")
        val assignee = context.createUser(TenantRole.EDITOR)

        val created = createTask(
            NewTask(
                " Kohte trocknen ", note = "Im Gemeindesaal", dueDate = LocalDate(2026, 11, 20), urgent = true,
                label = TaskLabel.CARE, assigneeId = assignee.id, materialIds = listOf(bulk.id), itemIds = listOf(item.id)
            )
        )
        assertEquals("Kohte trocknen", created.title)
        assertEquals(assignee.displayName, created.assigneeName)
        assertEquals(listOf(bulk.id), created.materialIds)
        assertEquals(listOf(item.id), created.itemIds)
        assertNull(created.completedAt)

        val changed: Task = put("tasks/${created.id}") {
            setBody(NewTask("Kohte trocknen", itemIds = listOf(item.id)))
        }.expect(HttpStatusCode.OK)
        assertEquals(emptyList(), changed.materialIds)
        assertNull(changed.assigneeId)
        assertNull(changed.note)

        // Filtered by material: links to the material itself or one of its items.
        assertEquals(listOf(created.id), tasksOf(kohte.id).map { it.id })
        assertEquals(emptyList(), tasksOf(bulk.id))

        assertEquals(HttpStatusCode.NoContent, delete("tasks/${created.id}").status)
        get("tasks/${created.id}").expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `invalid tasks are rejected`() = apiTest { context ->
        val stranger = context.createUser(role = null)
        // Invalid bodies can't be built as objects, so they are sent as JSON.
        post("tasks") { setBody("""{"title": " "}""") }.expectError(HttpStatusCode.BadRequest)
        post("tasks") { setBody("""{"title": "Prüfen", "repeatMonths": 0}""") }.expectError(HttpStatusCode.BadRequest)
        post("tasks") { setBody(NewTask("Prüfen", materialIds = listOf(Uuid.random()))) }.expectError(HttpStatusCode.BadRequest)
        post("tasks") { setBody(NewTask("Prüfen", itemIds = listOf(Uuid.random()))) }.expectError(HttpStatusCode.BadRequest)
        post("tasks") { setBody(NewTask("Prüfen", damageReportId = Uuid.random())) }.expectError(HttpStatusCode.BadRequest)
        val message = post("tasks") { setBody(NewTask("Prüfen", assigneeId = stranger.id)) }.expectError(HttpStatusCode.BadRequest)
        assertEquals("Die zuständige Person ist kein Mitglied dieses Stamms.", message)
    }

    @Test
    fun `a task from a damage report keeps the report`() = apiTest {
        val item = createItem(createIndividual(), "1")
        val report: DamageReport = post("damages") { setBody(NewDamageReport(item.id, "Riss")) }.expect(HttpStatusCode.Created)
        val task = createTask(NewTask("Riss nähen", itemIds = listOf(item.id), damageReportId = report.id))
        assertEquals(report.id, task.damageReportId)
        put("tasks/${task.id}") { setBody(NewTask("Riss nähen")) }.expectError(HttpStatusCode.BadRequest)

        // Deleting the report keeps the task.
        assertEquals(HttpStatusCode.NoContent, delete("damages/${report.id}").status)
        assertNull(get("tasks/${task.id}").expect<Task>(HttpStatusCode.OK).damageReportId)
    }

    @Test
    fun `completing a recurring task creates the next one, reopening undoes it`() = apiTest {
        val box = createBulk("Erste-Hilfe-Kasten")
        val task = createTask(
            NewTask("Prüfen", note = "[x] Pflaster\n[ ] Rettungsdecke", repeatMonths = 12, materialIds = listOf(box.id))
        )

        val done = complete(task.id, TaskCompletion(note = "2 Pflaster nachgekauft"))
        assertNotNull(done.task.completedAt)
        assertEquals("2 Pflaster nachgekauft", done.task.completionNote)
        val next = assertNotNull(done.next)
        assertEquals(today.plus(DatePeriod(months = 12)), next.dueDate)
        assertEquals("[ ] Pflaster\n[ ] Rettungsdecke", next.note)
        assertEquals(task.id, next.previousId)
        assertEquals(listOf(box.id), next.materialIds)
        post("tasks/${task.id}/complete") { setBody(TaskCompletion()) }.expectError(HttpStatusCode.Conflict)

        // The next due date can be chosen.
        val later = complete(next.id, TaskCompletion(nextDueDate = LocalDate(2028, 3, 1)))
        assertEquals(LocalDate(2028, 3, 1), later.next?.dueDate)

        // Reopening removes the open task the completion created.
        val reopened: Task = post("tasks/${next.id}/reopen").expect(HttpStatusCode.OK)
        assertNull(reopened.completedAt)
        assertNull(reopened.completionNote)
        get("tasks/${later.next!!.id}").expectError(HttpStatusCode.NotFound)
        post("tasks/${next.id}/reopen").expectError(HttpStatusCode.Conflict)

        // Reopening a task whose next one is completed too would fork the series.
        val again = assertNotNull(complete(next.id, TaskCompletion()).next)
        complete(again.id, TaskCompletion())
        val message = post("tasks/${next.id}/reopen").expectError(HttpStatusCode.Conflict)
        assertEquals("Die nächste Aufgabe dieser Reihe ist schon erledigt. Öffne zuerst sie wieder.", message)

        // A one-off task has no next one.
        assertNull(complete(createTask(NewTask("Aufräumen")).id, TaskCompletion()).next)
    }

    @Test
    fun `viewers change, complete and reopen only tasks assigned to them`() = apiTest { context ->
        val viewer = context.createUser(TenantRole.VIEWER)
        val asViewer = context.client(viewer.token)
        val own = createTask(NewTask("Erste-Hilfe-Kasten prüfen", note = "[ ] Pflaster", assigneeId = viewer.id))
        val other = createTask(NewTask("Kocher prüfen"))

        // Ticking the checklist is changing the task.
        val ticked: Task = asViewer.put("tasks/${own.id}") {
            setBody(NewTask("Erste-Hilfe-Kasten prüfen", note = "[x] Pflaster", assigneeId = viewer.id))
        }.expect(HttpStatusCode.OK)
        assertEquals("[x] Pflaster", ticked.note)
        val handedOn = asViewer.put("tasks/${own.id}") { setBody(NewTask("Erste-Hilfe-Kasten prüfen")) }
            .expectError(HttpStatusCode.Forbidden)
        assertEquals("Du kannst die Aufgabe nicht an jemand anderen abgeben.", handedOn)
        asViewer.put("tasks/${other.id}") { setBody(NewTask("Kocher prüfen", assigneeId = viewer.id)) }
            .expectError(HttpStatusCode.Forbidden)

        asViewer.post("tasks/${own.id}/complete") { setBody(TaskCompletion()) }.expect<CompletedTask>(HttpStatusCode.OK)
        assertEquals(viewer.displayName, get("tasks/${own.id}").expect<Task>(HttpStatusCode.OK).completedBy)
        asViewer.post("tasks/${own.id}/reopen").expect<Task>(HttpStatusCode.OK)
        asViewer.post("tasks/${other.id}/complete") { setBody(TaskCompletion()) }.expectError(HttpStatusCode.Forbidden)
        complete(other.id, TaskCompletion())
        asViewer.post("tasks/${other.id}/reopen").expectError(HttpStatusCode.Forbidden)
        asViewer.post("tasks") { setBody(NewTask("Neu")) }.expectError(HttpStatusCode.Forbidden)
        asViewer.delete("tasks/${own.id}").expectError(HttpStatusCode.Forbidden)
        assertEquals(2, asViewer.get("tasks").expect<List<Task>>(HttpStatusCode.OK).size)
    }

    @Test
    fun `new tasks from templates leave out assignees who left the Stamm`() = apiTest { context ->
        val leaver = context.createUser(TenantRole.EDITOR)
        val kocher = createIndividual("Gaskocher")
        createItem(kocher, "1")
        val recurring = createTask(NewTask("Zelte zählen", assigneeId = leaver.id, repeatMonths = 12))
        post("task-rules") { setBody(NewTaskRule("Schlauch tauschen", kocher.id, 60, assigneeId = leaver.id)) }
            .expect<TaskRule>(HttpStatusCode.Created)
        val routine: TaskRoutine = post("task-routines") {
            setBody(NewTaskRoutine("Vor dem Lager", "Lagerbeginn", listOf(NewRoutineStep(-1, "Packen", assigneeId = leaver.id))))
        }.expect(HttpStatusCode.Created)
        TestDatabase.execute("DELETE FROM tenant_members WHERE user_id = '${leaver.id}'")

        val ruleTask = tasksOf(kocher.id).single()
        assertEquals(leaver.id, ruleTask.assigneeId, "existing tasks keep their assignee")
        assertNull(assertNotNull(complete(ruleTask.id, TaskCompletion()).next).assigneeId)
        assertNull(assertNotNull(complete(recurring.id, TaskCompletion()).next).assigneeId)
        val before = tasksOf(kocher.id).map { it.id }.toSet()
        createItem(kocher, "2")
        assertNull(tasksOf(kocher.id).single { it.id !in before }.assigneeId)
        val started: List<Task> = post("task-routines/${routine.id}/start") {
            setBody(RoutineStart("Sommerlager", LocalDate(2027, 7, 20)))
        }.expect(HttpStatusCode.Created)
        assertNull(started.single().assigneeId)
    }

    @Test
    fun `a rule keeps one task per item, also for new items`() = apiTest { context ->
        val kocher = createIndividual("Gaskocher")
        val bought = post("items") {
            setBody(NewIndividualItem(kocher.variantId, "1", ItemCondition.GOOD, purchaseDate = LocalDate(2022, 3, 31)))
        }.expect<IndividualItem>(HttpStatusCode.Created)
        val unknownDate = createItem(kocher, "2")
        createItem(kocher, "3", status = ItemStatus.DECOMMISSIONED)
        val assignee = context.createUser(TenantRole.EDITOR)

        val rule: TaskRule = post("task-rules") {
            setBody(NewTaskRule("Gasschlauch tauschen", kocher.id, repeatMonths = 60, assigneeId = assignee.id))
        }.expect(HttpStatusCode.Created)
        assertEquals(assignee.displayName, rule.assigneeName)
        val tasks = get("tasks").expect<List<Task>>(HttpStatusCode.OK)
        assertEquals(2, tasks.size, "none for the decommissioned item")
        val byItem = tasks.associateBy { it.itemIds.single() }
        assertEquals(LocalDate(2027, 3, 31), byItem.getValue(bought.id).dueDate)
        assertEquals(today.plus(DatePeriod(months = 60)), byItem.getValue(unknownDate.id).dueDate)
        assertTrue(tasks.all { it.ruleId == rule.id && it.repeatMonths == 60 && it.assigneeId == assignee.id })

        val newItem = createItem(kocher, "4")
        assertEquals(1, tasksOf(kocher.id).count { newItem.id in it.itemIds })

        // The next task follows the rule as it is then.
        put("task-rules/${rule.id}") {
            setBody(NewTaskRule("Schlauch tauschen", kocher.id, repeatMonths = 48, assigneeId = null))
        }.expect<TaskRule>(HttpStatusCode.OK)
        val next = assertNotNull(complete(byItem.getValue(bought.id).id, TaskCompletion()).next)
        assertEquals("Schlauch tauschen", next.title)
        assertEquals(48, next.repeatMonths)
        assertNull(next.assigneeId)
        assertEquals(listOf(bought.id), next.itemIds)

        put("task-rules/${rule.id}") { setBody(NewTaskRule("X", createIndividual("Jurte").id, 12)) }
            .expectError(HttpStatusCode.BadRequest)
        post("task-rules") { setBody(NewTaskRule("Zählen", createBulk().id, 12)) }.expectError(HttpStatusCode.BadRequest)

        // Deleting the rule deletes its open tasks; the completed one stays.
        assertEquals(HttpStatusCode.NoContent, delete("task-rules/${rule.id}").status)
        val left = get("tasks").expect<List<Task>>(HttpStatusCode.OK)
        assertEquals(1, left.size)
        assertNotNull(left.single().completedAt)
        assertNull(left.single().ruleId)
    }

    @Test
    fun `a rule can count from today and starts with an unticked checklist`() = apiTest {
        val kocher = createIndividual("Gaskocher")
        post("items") {
            setBody(NewIndividualItem(kocher.variantId, "1", ItemCondition.GOOD, purchaseDate = LocalDate(2022, 3, 31)))
        }.expect<IndividualItem>(HttpStatusCode.Created)
        post("task-rules") {
            setBody(NewTaskRule("Prüfen", kocher.id, 12, fromPurchase = false, note = "[x] Dichtung\n[ ] Schlauch"))
        }.expect<TaskRule>(HttpStatusCode.Created)
        val task = tasksOf(kocher.id).single()
        assertEquals(today.plus(DatePeriod(months = 12)), task.dueDate)
        assertEquals("[ ] Dichtung\n[ ] Schlauch", task.note)
    }

    @Test
    fun `retired and deleted items end their rule tasks`() = apiTest {
        val kocher = createIndividual("Gaskocher")
        val lost = createItem(kocher, "1")
        val deleted = createItem(kocher, "2")
        post("task-rules") { setBody(NewTaskRule("Prüfen", kocher.id, 12)) }.expect<TaskRule>(HttpStatusCode.Created)
        val other = createTask(NewTask("Kocher 2 putzen", repeatMonths = 6, itemIds = listOf(deleted.id)))
        fun ruleTaskOf(tasks: List<Task>, item: IndividualItem) = tasks.single { it.ruleId != null && item.id in it.itemIds }

        // A retired item's open task stays, but completing it creates no next one.
        put("items/${lost.id}") { setBody(NewIndividualItem(kocher.variantId, "1", ItemCondition.GOOD, status = ItemStatus.LOST)) }
            .expect<IndividualItem>(HttpStatusCode.OK)
        val lostDone = complete(ruleTaskOf(tasksOf(kocher.id), lost).id, TaskCompletion())
        assertNull(lostDone.next)

        // Deleting an item deletes its open rule task (which would recur without an item); the completed one and
        // other tasks stay.
        val done = complete(ruleTaskOf(tasksOf(kocher.id), deleted).id, TaskCompletion())
        assertNotNull(done.next)
        assertEquals(HttpStatusCode.NoContent, delete("items/${deleted.id}").status)
        val left = get("tasks").expect<List<Task>>(HttpStatusCode.OK).map { it.id }.toSet()
        assertEquals(setOf(lostDone.task.id, done.task.id, other.id), left)

        // Found again, the item gets a rule task again, and only one however often it is changed.
        repeat(2) {
            put("items/${lost.id}") { setBody(NewIndividualItem(kocher.variantId, "1", ItemCondition.GOOD)) }
                .expect<IndividualItem>(HttpStatusCode.OK)
        }
        assertEquals(1, tasksOf(kocher.id).count { it.completedAt == null && it.ruleId != null })
    }

    @Test
    fun `routines create their tasks around a date`() = apiTest { context ->
        val gas = createBulk("Gasflasche")
        val routine: TaskRoutine = post("task-routines") {
            setBody(
                NewTaskRoutine(
                    "Vor dem Lager", "Lagerbeginn", listOf(
                        NewRoutineStep(-3, "Kocher prüfen", label = TaskLabel.INSPECTION),
                        NewRoutineStep(-14, "Gasvorrat prüfen", materialIds = listOf(gas.id)),
                        NewRoutineStep(2, "Zelte trocknen", urgent = true)
                    )
                )
            )
        }.expect(HttpStatusCode.Created)
        assertEquals(listOf(-14, -3, 2), routine.steps.map { it.offsetDays })
        assertEquals(listOf(gas.id), routine.steps.first().materialIds)
        val taken = post("task-routines") { setBody(NewTaskRoutine("Vor dem Lager", "Tag", listOf(NewRoutineStep(0, "X")))) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Es gibt schon eine Routine mit diesem Namen.", taken)

        val tasks: List<Task> = post("task-routines/${routine.id}/start") {
            setBody(RoutineStart("Pfingstlager", LocalDate(2027, 5, 15)))
        }.expect(HttpStatusCode.Created)
        assertEquals(
            listOf(LocalDate(2027, 5, 1), LocalDate(2027, 5, 12), LocalDate(2027, 5, 17)),
            tasks.map { it.dueDate }
        )
        assertTrue(tasks.all { it.routineId == routine.id && it.occasion == "Pfingstlager" })
        assertEquals(listOf(gas.id), tasks.first().materialIds)

        // Changing the routine leaves the tasks; deleting it keeps them with their occasion.
        put("task-routines/${routine.id}") {
            setBody(NewTaskRoutine("Vor jedem Lager", "Lagerbeginn", listOf(NewRoutineStep(-7, "Packen"))))
        }.expect<TaskRoutine>(HttpStatusCode.OK)
        assertEquals(HttpStatusCode.NoContent, delete("task-routines/${routine.id}").status)
        val left = get("tasks").expect<List<Task>>(HttpStatusCode.OK)
        assertEquals(3, left.size)
        assertTrue(left.all { it.routineId == null && it.occasion == "Pfingstlager" })
    }

    @Test
    fun `merging materials keeps the tasks, routine steps and rules about them`() = apiTest {
        val seil10 = createBulk("Seil 10 m")
        val seil15 = createBulk("Seil 15 m")
        val seil20 = createBulk("Seil 20 m")
        val withTarget = createTask(NewTask("Seile prüfen", materialIds = listOf(seil10.id, seil15.id)))
        val one = createTask(NewTask("Seil kaufen", materialIds = listOf(seil15.id)))
        val twoSources = createTask(NewTask("Lange Seile prüfen", materialIds = listOf(seil15.id, seil20.id)))
        val routine: TaskRoutine = post("task-routines") {
            setBody(NewTaskRoutine("Vor dem Lager", "Lagerbeginn", listOf(NewRoutineStep(-1, "Seile packen", materialIds = listOf(seil20.id)))))
        }.expect(HttpStatusCode.Created)
        post("materials/merge") {
            setBody(
                MaterialMerge(
                    listOf(seil10.id, seil15.id, seil20.id), "Seil",
                    mapOf(seil10.variantId to "10 m", seil15.variantId to "15 m", seil20.variantId to "20 m")
                )
            )
        }.expect<Material>(HttpStatusCode.OK)
        for (task in listOf(withTarget, one, twoSources)) {
            assertEquals(listOf(seil10.id), get("tasks/${task.id}").expect<Task>(HttpStatusCode.OK).materialIds, task.title)
        }
        assertEquals(listOf(seil10.id), get("task-routines/${routine.id}").expect<TaskRoutine>(HttpStatusCode.OK).steps.single().materialIds)

        // A merged material's rule moves along and now covers the remaining material's items too.
        val kocher = createIndividual("Gaskocher")
        val kocherAlt = createIndividual("Kocher")
        val big = createItem(kocher, "1")
        val small = createItem(kocherAlt, "1")
        val rule: TaskRule = post("task-rules") { setBody(NewTaskRule("Prüfen", kocherAlt.id, 12)) }.expect(HttpStatusCode.Created)
        post("materials/merge") {
            setBody(MaterialMerge(listOf(kocher.id, kocherAlt.id), "Gaskocher", mapOf(kocher.variantId to "Groß", kocherAlt.variantId to "Klein")))
        }.expect<Material>(HttpStatusCode.OK)
        assertEquals(kocher.id, get("task-rules/${rule.id}").expect<TaskRule>(HttpStatusCode.OK).materialId)
        val ruleTasks = tasksOf(kocher.id).filter { it.ruleId == rule.id }
        assertEquals(setOf(big.id, small.id), ruleTasks.flatMap { it.itemIds }.toSet())
        assertEquals(2, ruleTasks.size)
    }

    private suspend fun HttpClient.createTask(task: NewTask): Task =
        post("tasks") { setBody(task) }.expect(HttpStatusCode.Created)

    private suspend fun HttpClient.complete(id: Uuid, completion: TaskCompletion): CompletedTask =
        post("tasks/$id/complete") { setBody(completion) }.expect(HttpStatusCode.OK)

    private suspend fun HttpClient.tasksOf(materialId: Uuid): List<Task> =
        get("tasks?materialId=$materialId").expect(HttpStatusCode.OK)
}
