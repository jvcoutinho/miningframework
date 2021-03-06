package test

import org.junit.Test
import static test.Assert.assertEquals

public class DeletionTest {

    @Test
    public void multipleCommitsTest() {
        assertEquals(TestSuite.getModifiedLines('multipleCommits()'), "[34, 35] [] [32] []")
    }

    @Test
    public void deletionBothAndAdditionTest() {
        assertEquals(TestSuite.getModifiedLines('deletionBothAndAddition()'),'[] [[11, 11]] [12] [[11, 11]]')
    }

    @Test
    public void deletionSingleAndAdditionTest() {
        assertEquals(TestSuite.getModifiedLines('deletionSingleAndAddition()'),'[] [[5, 5]] [6] []')
    }
    
}