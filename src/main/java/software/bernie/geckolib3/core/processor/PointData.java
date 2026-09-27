package software.bernie.geckolib3.core.processor;

public class PointData {

    public float rotationValueX;
    public float rotationValueY;
    public float rotationValueZ;

    /** 本 tick 的分量清零（表跨 tick 复用，见 AnimationProcessor#pointDataGroupForThisTick）。 */
    public void reset() {
        this.rotationValueX = 0f;
        this.rotationValueY = 0f;
        this.rotationValueZ = 0f;
    }
}
