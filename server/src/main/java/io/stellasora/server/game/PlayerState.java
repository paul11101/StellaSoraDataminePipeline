package io.stellasora.server.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class PlayerState {
    public int schemaVersion = 1;
    public long revision;
    public long uid = 10001L;
    public String nickname = "Stella";
    public int hashtag = 1001;
    public int headIcon = 103;
    public int worldClass = 1;
    public int worldStage = 1;
    public long createdAt = Instant.now().getEpochSecond();
    public int primaryEnergy = 200;
    public int secondaryEnergy;
    public long energyUpdatedAt = Instant.now().getEpochSecond();
    public Map<Integer, Integer> resources = new TreeMap<>();
    public Map<Integer, Integer> items = new TreeMap<>();
    public Map<Integer, CharacterState> characters = new TreeMap<>();
    public Set<Integer> storyPassed = new TreeSet<>();
    public Map<Integer, StoryState> storyChoices = new TreeMap<>();
    public Set<Integer> storyEvidences = new TreeSet<>();
    public long storyBuildId;
    public LastReadState lastRead = new LastReadState();
    public Set<Integer> tutorialPassed = new TreeSet<>();
    public Set<Integer> tutorialRewards = new TreeSet<>();
    public Set<String> storySetRewards = new TreeSet<>();
    public Map<Integer, Integer> newbieSteps = new TreeMap<>();
    public List<Integer> board = new ArrayList<>();

    public static PlayerState createDefault(GameDataIndex data) {
        PlayerState state = new PlayerState();
        state.resources.put(1, 999_999);
        state.resources.put(2, 9_999);
        state.newbieSteps.put(1, -1);
        data.characters().stream()
                .sorted(java.util.Comparator.comparingInt(GameDataIndex.Entry::id))
                .limit(3)
                .forEach(entry -> state.characters.put(
                        entry.id(), new CharacterState(entry.id(), 1, 0, entry.id() * 100 + 1)));
        state.resetDefaultBoard();
        return state;
    }

    public synchronized void normalize() {
        if (nickname == null || nickname.isBlank()) {
            nickname = "Stella";
        }
        resources = resources == null ? new TreeMap<>() : new TreeMap<>(resources);
        items = items == null ? new TreeMap<>() : new TreeMap<>(items);
        characters = characters == null ? new TreeMap<>() : new TreeMap<>(characters);
        storyPassed = storyPassed == null ? new TreeSet<>() : new TreeSet<>(storyPassed);
        storyChoices = storyChoices == null ? new TreeMap<>() : new TreeMap<>(storyChoices);
        storyChoices.replaceAll((id, value) -> {
            StoryState normalized = value == null ? new StoryState() : value;
            normalized.id = id;
            normalized.normalize();
            return normalized;
        });
        storyEvidences = storyEvidences == null ? new TreeSet<>() : new TreeSet<>(storyEvidences);
        lastRead = lastRead == null ? new LastReadState() : lastRead;
        tutorialPassed = tutorialPassed == null ? new TreeSet<>() : new TreeSet<>(tutorialPassed);
        tutorialRewards = tutorialRewards == null ? new TreeSet<>() : new TreeSet<>(tutorialRewards);
        storySetRewards = storySetRewards == null ? new TreeSet<>() : new TreeSet<>(storySetRewards);
        newbieSteps = newbieSteps == null ? new TreeMap<>() : new TreeMap<>(newbieSteps);
        newbieSteps.putIfAbsent(1, -1);
        board = board == null ? new ArrayList<>() : new ArrayList<>(board);
        if (board.isEmpty()) {
            resetDefaultBoard();
        }
        primaryEnergy = Math.max(0, primaryEnergy);
        secondaryEnergy = Math.max(0, secondaryEnergy);
        if (energyUpdatedAt <= 0) {
            energyUpdatedAt = Instant.now().getEpochSecond();
        }
    }

    public synchronized void grantCharacter(int id, int level) {
        int normalizedLevel = Math.max(1, Math.min(level, 100));
        CharacterState character = characters.get(id);
        if (character == null) {
            character = new CharacterState(id, normalizedLevel, 0, id * 100 + 1);
            characters.put(id, character);
        } else {
            character.level = normalizedLevel;
        }
        revision++;
    }

    public synchronized void grantItem(int id, int quantity, boolean resource) {
        Map<Integer, Integer> target = resource ? resources : items;
        target.merge(id, quantity, Math::addExact);
        if (target.get(id) <= 0) {
            target.remove(id);
        }
        revision++;
    }

    public synchronized void changeWorldClass(int value) {
        worldClass = Math.max(1, value);
        revision++;
    }

    public synchronized void completeStory(int storyId) {
        storyPassed.add(storyId);
        revision++;
    }

    public synchronized void startStory(long buildId) {
        storyBuildId = buildId;
        revision++;
    }

    public synchronized void settleStory(
            int storyId,
            Map<Integer, Integer> major,
            Map<Integer, Integer> personality) {
        storyPassed.add(storyId);
        StoryState story = storyChoices.computeIfAbsent(storyId, ignored -> new StoryState());
        story.id = storyId;
        story.major.putAll(major);
        story.personality.putAll(personality);
        revision++;
    }

    public synchronized void addEvidence(int evidenceId) {
        storyEvidences.add(evidenceId);
        revision++;
    }

    public synchronized void passTutorial(int levelId) {
        tutorialPassed.add(levelId);
        revision++;
    }

    public synchronized void rewardTutorial(int levelId) {
        tutorialRewards.add(levelId);
        revision++;
    }

    public synchronized void rewardStorySet(int chapterId, int sectionId) {
        storySetRewards.add(chapterId + ":" + sectionId);
        revision++;
    }

    public synchronized void updateLastRead(LastReadState value) {
        lastRead = value.copy();
        revision++;
    }

    public synchronized List<CharacterState> characterList() {
        return new ArrayList<>(characters.values());
    }

    public synchronized Map<Integer, Integer> resourceSnapshot() {
        return Map.copyOf(resources);
    }

    public synchronized Map<Integer, Integer> itemSnapshot() {
        return Map.copyOf(items);
    }

    public synchronized List<StoryState> storyList() {
        Set<Integer> ids = new TreeSet<>(storyPassed);
        ids.addAll(storyChoices.keySet());
        List<StoryState> result = new ArrayList<>();
        for (int id : ids) {
            StoryState value = storyChoices.get(id);
            result.add(value == null ? new StoryState(id) : value.copy());
        }
        return result;
    }

    public synchronized Set<Integer> storyEvidenceSnapshot() {
        return Set.copyOf(storyEvidences);
    }

    public synchronized Set<Integer> tutorialPassedSnapshot() {
        return Set.copyOf(tutorialPassed);
    }

    public synchronized Set<Integer> tutorialRewardSnapshot() {
        return Set.copyOf(tutorialRewards);
    }

    public synchronized Set<String> storySetRewardSnapshot() {
        return Set.copyOf(storySetRewards);
    }

    public synchronized Map<Integer, Integer> newbieStepSnapshot() {
        return Map.copyOf(newbieSteps);
    }

    public synchronized List<Integer> boardSnapshot() {
        return List.copyOf(board);
    }

    public synchronized EnergyState energySnapshot() {
        return new EnergyState(primaryEnergy, secondaryEnergy, energyUpdatedAt);
    }

    public synchronized void learnNewbie(int groupId, int stepId) {
        newbieSteps.put(groupId, stepId);
        revision++;
    }

    public synchronized void updateBoard(List<Integer> ids) {
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        for (int id : ids) {
            if (id > 0) {
                unique.add(id);
            }
        }
        if (!unique.isEmpty()) {
            board = new ArrayList<>(unique);
            revision++;
        }
    }

    private void resetDefaultBoard() {
        board = characters.values().stream()
                .map(character -> character.skin)
                .filter(skin -> skin > 0)
                .limit(3)
                .map(skin -> 400_000 + skin)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public synchronized LastReadState lastReadSnapshot() {
        return lastRead.copy();
    }

    public synchronized long storyBuildIdSnapshot() {
        return storyBuildId;
    }

    public static final class StoryState {
        public int id;
        public Map<Integer, Integer> major = new TreeMap<>();
        public Map<Integer, Integer> personality = new TreeMap<>();

        public StoryState() {}

        public StoryState(int id) {
            this.id = id;
        }

        private void normalize() {
            major = major == null ? new TreeMap<>() : new TreeMap<>(major);
            personality = personality == null ? new TreeMap<>() : new TreeMap<>(personality);
        }

        private StoryState copy() {
            StoryState result = new StoryState(id);
            result.major.putAll(major);
            result.personality.putAll(personality);
            return result;
        }
    }

    public static final class LastReadState {
        public int type;
        public int storyId;
        public int storySetChapterId;
        public int storySetSectionId;
        public int activityChapterId;
        public int activityStoryId;

        public LastReadState copy() {
            LastReadState result = new LastReadState();
            result.type = type;
            result.storyId = storyId;
            result.storySetChapterId = storySetChapterId;
            result.storySetSectionId = storySetSectionId;
            result.activityChapterId = activityChapterId;
            result.activityStoryId = activityStoryId;
            return result;
        }
    }

    public static final class CharacterState {
        public int id;
        public int level;
        public int experience;
        public int skin;
        public int affinityLevel = 1;
        public long createTime = Instant.now().getEpochSecond();

        public CharacterState() {}

        public CharacterState(int id, int level, int experience, int skin) {
            this.id = id;
            this.level = level;
            this.experience = experience;
            this.skin = skin;
        }
    }

    public record EnergyState(int primary, int secondary, long updatedAt) {}
}
