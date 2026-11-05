package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.pocketpath.domain.model.Category
import com.pocketpath.domain.model.CommitmentClass
import org.junit.Assert.*
import org.junit.Test

class CategoryCodecTest {

    private val audit = Timestamp(1_791_000_000L, 0)

    @Test
    fun categoryDataEncodesCustomCategoryCorrectly() {
        val category = Category(
            id = "custom_cat_1",
            name = "Education",
            defaultCommitmentClass = CommitmentClass.UNPLANNED_ESSENTIAL,
            iconKey = "school",
            sortOrder = 5,
        )

        val encoded = FoundationCodec.categoryData(category)

        assertEquals("Education", encoded["name"])
        assertEquals("UNPLANNED_ESSENTIAL", encoded["defaultCommitmentClass"])
        assertEquals(5L, encoded["sortOrder"])
        assertEquals("school", encoded["iconKey"])
        assertEquals(0L, encoded["revision"])
    }

    @Test
    fun categoryDecodesFirestoreFieldsCorrectly() {
        val rawFields = mapOf(
            "name" to "Entertainment",
            "defaultCommitmentClass" to "DISCRETIONARY",
            "sortOrder" to 3L,
            "iconKey" to "movie",
            "createdAt" to audit,
            "updatedAt" to audit,
            "revision" to 0L,
        )

        val decoded = FoundationCodec.category("custom_cat_2", rawFields)

        assertEquals("custom_cat_2", decoded.id)
        assertEquals("Entertainment", decoded.name)
        assertEquals(CommitmentClass.DISCRETIONARY, decoded.defaultCommitmentClass)
        assertEquals("movie", decoded.iconKey)
        assertEquals(3, decoded.sortOrder)
        assertEquals(0L, decoded.metadata.revision)
    }

    @Test
    fun sameCategoryComparesFieldsIgnoringAuditTimestamps() {
        val category = Category(
            id = "custom_cat_3",
            name = "Health",
            defaultCommitmentClass = CommitmentClass.COMMITTED,
            sortOrder = 1,
        )

        val first = FoundationCodec.categoryData(category) + mapOf(
            "createdAt" to audit,
            "updatedAt" to audit,
        )

        val retry = FoundationCodec.categoryData(category) + mapOf(
            "createdAt" to Timestamp(audit.seconds + 30, 0),
            "updatedAt" to Timestamp(audit.seconds + 60, 0),
        )

        assertTrue(FoundationCodec.sameCategory(first, retry))
    }

    @Test
    fun invalidCategoryDocumentIsRejected() {
        val invalidMissingField = mapOf(
            "name" to "Incomplete",
            "createdAt" to audit,
            "updatedAt" to audit,
            "revision" to 0L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.category("bad_id", invalidMissingField)
        }
    }
}
