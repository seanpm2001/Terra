package io.left.rightmesh.libdtncommon.data;

/**
 * @author Lucien Loiseau on 17/09/18.
 */
public class BlockIntegrityBlock extends CanonicalBlock {

    public static final int type = 2;

    public BlockIntegrityBlock() {
        super(type);
    }

}
