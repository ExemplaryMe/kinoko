package kinoko.packet.world.message;

import kinoko.server.packet.OutPacket;
import kinoko.world.Encodable;

public final class DropPickUpMessageInfo implements Encodable {
    private final DropPickUpMessageType type;
    private boolean portionNotFound;
    private int money;
    private int itemId;
    private int itemCount;

    public DropPickUpMessageInfo(DropPickUpMessageType type) {
        this.type = type;
    }

    public void setPortionNotFound(boolean portionNotFound) {
        this.portionNotFound = portionNotFound;
    }

    public void setMoney(int money) {
        this.money = money;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    @Override
    public void encode(OutPacket outPacket) {
        outPacket.encodeByte(type.getValue());
        switch (type) {
            case MONEY -> {
                outPacket.encodeByte(portionNotFound);
                outPacket.encodeInt(money);
                outPacket.encodeShort(0); // Internet Cafe Meso Bonus
            }
            case ITEM_BUNDLE -> {
                outPacket.encodeInt(itemId); // nItemID
                outPacket.encodeInt(itemCount);
            }
            case ITEM_SINGLE -> {
                outPacket.encodeInt(itemId);
            }
        }
    }

}