import java.util.*;

public class Wicket {
    public WicketType wicketType;
    public Player takenBy;
    public OverDetails overDetail;
    public BallDetails ballDetail;

    public Wicket(WicketType wicketType, Player takenBy, OverDetails overDetail, BallDetails ballDetail) {
        this.wicketType = wicketType;
        this.takenBy = takenBy;
        this.overDetail = overDetail;
        this.ballDetail = ballDetail;
    }
};