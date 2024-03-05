package kinoko.handler.stage;

import kinoko.database.DatabaseManager;
import kinoko.handler.Handler;
import kinoko.packet.stage.LoginPacket;
import kinoko.packet.stage.LoginType;
import kinoko.provider.EtcProvider;
import kinoko.provider.ItemProvider;
import kinoko.provider.SkillProvider;
import kinoko.provider.item.ItemInfo;
import kinoko.provider.skill.SkillInfo;
import kinoko.server.ChannelServer;
import kinoko.server.Server;
import kinoko.server.ServerConfig;
import kinoko.server.client.Client;
import kinoko.server.client.MigrationRequest;
import kinoko.server.header.InHeader;
import kinoko.server.packet.InPacket;
import kinoko.world.Account;
import kinoko.world.GameConstants;
import kinoko.world.World;
import kinoko.world.item.*;
import kinoko.world.job.Job;
import kinoko.world.job.LoginJob;
import kinoko.world.quest.QuestManager;
import kinoko.world.skill.SkillManager;
import kinoko.world.user.CharacterData;
import kinoko.world.user.funckey.FuncKeyManager;
import kinoko.world.user.stat.CharacterStat;
import kinoko.world.user.stat.ExtendSp;
import kinoko.world.user.stat.StatConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class LoginHandler {
    private static final Logger log = LogManager.getLogger(LoginHandler.class);

    @Handler(InHeader.CHECK_PASSWORD)
    public static void handleCheckPassword(Client c, InPacket inPacket) {
        final String username = inPacket.decodeString();
        final String password = inPacket.decodeString();
        final byte[] machineId = inPacket.decodeArray(16);
        final int gameRoomClient = inPacket.decodeInt();
        final byte gameStartMode = inPacket.decodeByte();
        final byte worldId = inPacket.decodeByte();
        final byte channelId = inPacket.decodeByte();
        final byte[] address = inPacket.decodeArray(4);

        final Optional<Account> accountResult = DatabaseManager.accountAccessor().getAccountByUsername(username);
        if (accountResult.isEmpty()) {
            if (ServerConfig.AUTO_CREATE_ACCOUNT) {
                DatabaseManager.accountAccessor().newAccount(username, password);
            }
            c.write(LoginPacket.checkPasswordResultFail(LoginType.NOT_REGISTERED));
            return;
        }

        final Account account = accountResult.get();
        if (Server.isConnected(account)) {
            c.write(LoginPacket.checkPasswordResultFail(LoginType.ALREADY_CONNECTED));
            return;
        }
        if (!DatabaseManager.accountAccessor().checkPassword(account, password, false)) {
            c.write(LoginPacket.checkPasswordResultFail(LoginType.INCORRECT_PASSWORD));
            return;
        }

        c.setAccount(account);
        c.setMachineId(machineId);
        c.getConnectedServer().getClientStorage().addClient(c);
        c.write(LoginPacket.checkPasswordResultSuccess(account, c.getClientKey()));
    }

    @Handler({ InHeader.WORLD_INFO_REQUEST, InHeader.WORLD_REQUEST })
    public static void handleWorldRequest(Client c, InPacket inPacket) {
        for (World world : Server.getWorlds()) {
            c.write(LoginPacket.worldInformation(world));
        }
        c.write(LoginPacket.worldInformationEnd());
        c.write(LoginPacket.latestConnectedWorld(ServerConfig.WORLD_ID));
    }

    @Handler(InHeader.VIEW_ALL_CHAR)
    public static void handleViewAllChar(Client c, InPacket inPacket) {
        c.write(LoginPacket.viewAllCharResult());
    }

    @Handler(InHeader.CHECK_USER_LIMIT)
    public static void handleCheckUserLimit(Client c, InPacket inPacket) {
        final int worldId = inPacket.decodeShort();
        c.write(LoginPacket.checkUserLimitResult());
    }

    @Handler(InHeader.SELECT_WORLD)
    public static void handleSelectWorld(Client c, InPacket inPacket) {
        final byte gameStartMode = inPacket.decodeByte();
        if (gameStartMode != 2) {
            c.write(LoginPacket.selectWorldResultFail(LoginType.UNKNOWN));
            return;
        }

        final byte worldId = inPacket.decodeByte();
        final byte channelId = inPacket.decodeByte();
        inPacket.decodeInt(); // unk

        // Check World ID and Channel ID
        final Optional<ChannelServer> channelResult = Server.getChannelServerById(worldId, channelId);
        if (channelResult.isEmpty()) {
            c.write(LoginPacket.selectWorldResultFail(LoginType.UNKNOWN));
            return;
        }

        // Check Account
        final Account account = c.getAccount();
        if (account == null) {
            c.write(LoginPacket.selectWorldResultFail(LoginType.UNKNOWN));
            return;
        }
        if (!c.getConnectedServer().getClientStorage().isConnected(account)) {
            c.write(LoginPacket.selectWorldResultFail(LoginType.UNKNOWN));
            return;
        }

        loadCharacterList(c);
        account.setWorldId(worldId);
        account.setChannelId(channelId);
        c.write(LoginPacket.selectWorldResultSuccess(account));
    }

    @Handler(InHeader.CHECK_DUPLICATED_ID)
    public static void handleCheckDuplicatedId(Client c, InPacket inPacket) {
        final String name = inPacket.decodeString();
        // Validation done on client side, server side validation in NEW_CHAR handler
        if (DatabaseManager.characterAccessor().checkCharacterNameAvailable(name)) {
            c.write(LoginPacket.checkDuplicatedIdResult(name, 0)); // Success
        } else {
            c.write(LoginPacket.checkDuplicatedIdResult(name, 1)); // This name is currently being used.
        }
    }

    @Handler(InHeader.CREATE_NEW_CHARACTER)
    public static void handleCreateNewCharacter(Client c, InPacket inPacket) {
        final String name = inPacket.decodeString();
        final int selectedRace = inPacket.decodeInt();
        final short selectedSubJob = inPacket.decodeShort();
        final int[] selectedAL = new int[]{
                inPacket.decodeInt(), // face
                inPacket.decodeInt(), // hair
                inPacket.decodeInt(), // hair color
                inPacket.decodeInt(), // skin
                inPacket.decodeInt(), // coat
                inPacket.decodeInt(), // pants
                inPacket.decodeInt(), // shoes
                inPacket.decodeInt(), // weapon
        };
        final byte gender = inPacket.decodeByte();

        // Validate character
        if (!GameConstants.isValidCharacterName(name) || EtcProvider.isForbiddenName(name)) {
            c.write(LoginPacket.createNewCharacterResultFail(LoginType.INVALID_CHARACTER_NAME));
            return;
        }
        Optional<LoginJob> loginJob = LoginJob.getByRace(selectedRace);
        if (loginJob.isEmpty()) {
            c.close();
            return;
        }
        Job job = loginJob.get().getJob();
        if (selectedSubJob != 0 && job != Job.BEGINNER) {
            c.close();
            return;
        }
        for (int i = 0; i < selectedAL.length; i++) {
            if (!EtcProvider.isValidStartingItem(i, selectedAL[i])) {
                c.close();
                return;
            }
        }
        if (gender < 0 || gender > 2) {
            c.close();
            return;
        }

        // Create character
        final Optional<Integer> characterIdResult = DatabaseManager.characterAccessor().nextCharacterId();
        if (characterIdResult.isEmpty()) {
            c.write(LoginPacket.createNewCharacterResultFail(LoginType.TIMEOUT));
            return;
        }
        final CharacterData characterData = new CharacterData(c.getAccount().getId());
        characterData.setItemSnCounter(new AtomicInteger(1));

        // Initial Stats
        final short level = 1;
        final int hp = StatConstants.getMinHp(level, job.getJobId());
        final int mp = StatConstants.getMinMp(level, job.getJobId());
        final CharacterStat cs = new CharacterStat();
        cs.setId(characterIdResult.get());
        cs.setName(name);
        cs.setGender(gender);
        cs.setSkin((byte) selectedAL[3]);
        cs.setFace(selectedAL[0]);
        cs.setHair(selectedAL[1] + selectedAL[2]);
        cs.setLevel(level);
        cs.setJob(job.getJobId());
        cs.setSubJob(selectedSubJob);
        cs.setBaseStr((short) 12);
        cs.setBaseDex((short) 5);
        cs.setBaseInt((short) 4);
        cs.setBaseLuk((short) 4);
        cs.setHp(hp);
        cs.setMaxHp(hp);
        cs.setMp(mp);
        cs.setMaxMp(mp);
        cs.setAp((short) 0);
        cs.setSp(ExtendSp.from(Map.of()));
        cs.setExp(0);
        cs.setPop((short) 0);
        cs.setPosMap(10000);
        cs.setPortal((byte) 0);
        characterData.setCharacterStat(cs);

        // Initialize inventory and add starting equips
        final InventoryManager im = new InventoryManager();
        im.setEquipped(new Inventory(Short.MAX_VALUE));
        im.setEquipInventory(new Inventory(ServerConfig.INVENTORY_BASE_SLOTS));
        im.setConsumeInventory(new Inventory(ServerConfig.INVENTORY_BASE_SLOTS));
        im.setInstallInventory(new Inventory(ServerConfig.INVENTORY_BASE_SLOTS));
        im.setEtcInventory(new Inventory(ServerConfig.INVENTORY_BASE_SLOTS));
        im.setCashInventory(new Inventory(ServerConfig.INVENTORY_CASH_SLOTS));
        im.setMoney(0);
        characterData.setInventoryManager(im);

        for (int i = 4; i < selectedAL.length; i++) {
            final int itemId = selectedAL[i];
            if (itemId == 0) {
                continue;
            }
            final BodyPart bodyPart;
            if (i == 4) {
                bodyPart = BodyPart.COAT;
            } else if (i == 5) {
                bodyPart = BodyPart.PANTS;
            } else if (i == 6) {
                bodyPart = BodyPart.SHOES;
            } else { // i == 7
                bodyPart = BodyPart.WEAPON;
            }
            if (!ItemConstants.isCorrectBodyPart(itemId, bodyPart, gender)) {
                log.error("Incorrect body part {} for item {}", bodyPart.name(), itemId);
                continue;
            }
            final Optional<ItemInfo> itemInfoResult = ItemProvider.getItemInfo(itemId);
            if (itemInfoResult.isEmpty()) {
                log.error("Failed to resolve item {}", itemId);
                continue;
            }
            final ItemInfo ii = itemInfoResult.get();
            final Item item = ii.createItem(characterData.getNextItemSn());
            im.getEquipped().putItem(bodyPart.getValue(), item);
        }

        // Initialize Skills
        final SkillManager sm = new SkillManager();
        for (SkillInfo skillInfo : SkillProvider.getSkillsForJob(job)) {
            if (skillInfo.isInvisible()) {
                continue;
            }
            sm.addSkill(skillInfo.createRecord());
        }
        characterData.setSkillManager(sm);

        // Initialize Quest Manager
        final QuestManager qm = new QuestManager();
        characterData.setQuestManager(qm);

        // Initialize Func Key Manager
        final FuncKeyManager fkm = new FuncKeyManager();
        fkm.updateFuncKeyMap(GameConstants.DEFAULT_FUNC_KEY_MAP);
        fkm.setQuickslotKeyMap(GameConstants.DEFAULT_QUICKSLOT_KEY_MAP);
        characterData.setFuncKeyManager(fkm);

        // Save character
        if (DatabaseManager.characterAccessor().newCharacter(characterData)) {
            loadCharacterList(c);
            c.write(LoginPacket.createNewCharacterResultSuccess(characterData));
        } else {
            c.write(LoginPacket.createNewCharacterResultFail(LoginType.TIMEOUT));
        }
    }

    @Handler(InHeader.SELECT_CHARACTER)
    public static void handleSelectCharacter(Client c, InPacket inPacket) {
        final int characterId = inPacket.decodeInt();
        final String macAddress = inPacket.decodeString(); // CLogin::GetLocalMacAddress
        final String macAddressWithHddSerial = inPacket.decodeString(); // CLogin::GetLocalMacAddressWithHDDSerialNo

        final Account account = c.getAccount();
        if (ServerConfig.REQUIRE_SECONDARY_PASSWORD || account == null || !account.canSelectCharacter(characterId)) {
            c.write(LoginPacket.selectCharacterResultFail(LoginType.UNKNOWN, 2));
            return;
        }
        tryMigration(c, account, characterId);
    }

    @Handler(InHeader.DELETE_CHARACTER)
    public static void handleDeleteCharacter(Client c, InPacket inPacket) {
        final String secondaryPassword = inPacket.decodeString();
        final int characterId = inPacket.decodeInt();

        final Account account = c.getAccount();
        if (account == null || !account.canSelectCharacter(characterId) ||
                !c.getConnectedServer().getClientStorage().isConnected(account)) {
            c.write(LoginPacket.deleteCharacterResult(LoginType.UNKNOWN, characterId));
            return;
        }
        if (!DatabaseManager.accountAccessor().checkPassword(account, secondaryPassword, true)) {
            c.write(LoginPacket.deleteCharacterResult(LoginType.INCORRECT_SPW, characterId));
            return;
        }
        if (!DatabaseManager.characterAccessor().deleteCharacter(account.getId(), characterId)) {
            c.write(LoginPacket.deleteCharacterResult(LoginType.DB_FAIL, characterId));
            return;
        }

        loadCharacterList(c);
        c.write(LoginPacket.deleteCharacterResult(LoginType.SUCCESS, characterId));
    }

    @Handler(InHeader.ENABLE_SPW_REQUEST)
    public static void handleEnableSpwRequest(Client c, InPacket inPacket) {
        inPacket.decodeByte(); // 1
        final int characterId = inPacket.decodeInt(); // dwCharacterID
        final String macAddress = inPacket.decodeString(); // CLogin::GetLocalMacAddress
        final String macAddressWithHddSerial = inPacket.decodeString(); // CLogin::GetLocalMacAddressWithHDDSerialNo
        final String secondaryPassword = inPacket.decodeString(); // sSPW

        final Account account = c.getAccount();
        if (account == null || !account.canSelectCharacter(characterId) || account.hasSecondaryPassword() ||
                !DatabaseManager.accountAccessor().savePassword(account, "", secondaryPassword, true)) {
            c.write(LoginPacket.selectCharacterResultFail(LoginType.UNKNOWN, 2));
            return;
        }
        tryMigration(c, account, characterId);
    }

    @Handler(InHeader.CHECK_SPW_REQUEST)
    public static void handleCheckSpwRequest(Client c, InPacket inPacket) {
        final String secondaryPassword = inPacket.decodeString(); // sSPW
        final int characterId = inPacket.decodeInt(); // dwCharacterID
        final String macAddress = inPacket.decodeString(); // CLogin::GetLocalMacAddress
        final String macAddressWithHddSerial = inPacket.decodeString(); // CLogin::GetLocalMacAddressWithHDDSerialNo

        final Account account = c.getAccount();
        if (account == null || !account.canSelectCharacter(characterId) || !account.hasSecondaryPassword()) {
            c.write(LoginPacket.selectCharacterResultFail(LoginType.UNKNOWN, 2));
            return;
        }
        if (!DatabaseManager.accountAccessor().checkPassword(account, secondaryPassword, true)) {
            c.write(LoginPacket.checkSecondaryPasswordResult());
            return;
        }
        tryMigration(c, account, characterId);
    }

    private static void loadCharacterList(Client c) {
        final Account account = c.getAccount();
        account.setCharacterList(DatabaseManager.characterAccessor().getAvatarDataByAccount(account.getId()));
    }

    private static void tryMigration(Client c, Account account, int characterId) {
        final Optional<ChannelServer> channelResult = Server.getChannelServerById(account.getWorldId(), account.getChannelId());
        if (channelResult.isEmpty()) {
            log.error("Failed to submit migration request for character ID : {}", characterId);
            c.write(LoginPacket.selectCharacterResultFail(LoginType.UNKNOWN, 2));
            return;
        }
        final ChannelServer channelServer = channelResult.get();
        final Optional<MigrationRequest> mrResult = Server.submitMigrationRequest(c, channelServer, characterId);
        if (mrResult.isEmpty()) {
            log.error("Failed to submit migration request for character ID : {}", characterId);
            c.write(LoginPacket.selectCharacterResultFail(LoginType.UNKNOWN, 2));
            return;
        }
        c.write(LoginPacket.selectCharacterResultSuccess(channelServer.getAddress(), channelServer.getPort(), characterId));
    }
}
