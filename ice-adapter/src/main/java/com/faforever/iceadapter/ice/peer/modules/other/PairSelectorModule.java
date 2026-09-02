package com.faforever.iceadapter.ice.peer.modules.other;

import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.util.LockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

@Slf4j
@RequiredArgsConstructor
public class PairSelectorModule implements ModuleBase, PeerEventListener {
    private final Peer peer;
    private Component component;
    private Lock lockComponent;

    @Override
    public void init() {
        peer.addEventListener(this);
        lockComponent = peer.getLock("LockComponent");
        peer.setKeepAliveStrategy(KeepAliveStrategy.ALL_SUCCEEDED);
    }

    @Override
    public void onIceComponentChange(Peer peer, Component component) {
        LockUtil.executeWithLock(lockComponent, () -> this.component = component);
    }

    /**
     * Устанавливает активную пару для компонента.
     * Использует готовую логику ice4j: component.setSelectedPair(pair)
     */
    public void selectPair(CandidatePair pair) {
        if (pair == null || component == null) return;

        LockUtil.executeWithLock(lockComponent, () -> {
            // Проверяем, что пара в succeeded состоянии
            if (pair.getState() != CandidatePairState.SUCCEEDED) {
                log.warn("Pair not SUCCEEDED, skipping: {}", pair.getState());
                return;
            }

            // Проверяем, что пара принадлежит этому компоненту
            if (pair.getParentComponent() != component) {
                log.warn("Pair belongs to different component, skipping");
                return;
            }

            // setSelectedPair protected, используем reflection
            try {
                Method setSelectedPair = Component.class.getDeclaredMethod("setSelectedPair", CandidatePair.class);
                setSelectedPair.setAccessible(true);
                setSelectedPair.invoke(component, pair);
                log.info("Switched to pair: {} <-> {}",
                        pair.getLocalCandidate().getType(),
                        pair.getRemoteCandidate().getType());
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                log.error("Failed to call setSelectedPair", e);
            }
        });
    }

    /**
     * Возвращает список succeeded пар из checkList.
     * Используем публичный API: component.getParentStream().getCheckList()
     */
    public List<CandidatePair> getSucceededPairs() {
        if (component == null) return List.of();
        List<CandidatePair> result = new ArrayList<>();
        CheckList checkList = component.getParentStream().getCheckList();
        if (checkList != null) {
            for (CandidatePair pair : checkList) {
                if (pair.getState() == CandidatePairState.SUCCEEDED) {
                    // Проверяем, что пара принадлежит этому компоненту
                    if (pair.getParentComponent() == component) {
                        result.add(pair);
                    }
                }
            }
        }
        return result;
    }
}
