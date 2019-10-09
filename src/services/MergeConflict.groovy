package services

import org.apache.commons.lang3.StringUtils

class MergeConflict {

    public static MINE_CONFLICT_MARKER = "<<<<<<< MINE"
    public static YOURS_CONFLICT_MARKER = ">>>>>>> YOURS"
    public static CHANGE_CONFLICT_MARKER = "======="

    private String left
    private String right

    MergeConflict(String left, String right) {
        this.left = left
        this.right = right
    }

    @Override
    boolean equals(Object o) {
        return StringUtils.deleteWhitespace(left) == StringUtils.deleteWhitespace(((MergeConflict) o).left)
                && StringUtils.deleteWhitespace(right) == StringUtils.deleteWhitespace(((MergeConflict) o).right)
    }

}
