package jab.spigot.smartboards;

import com.comphenix.protocol.ProtocolManager;
import jab.spigot.smartboards.boards.SmartBoard;
import jab.spigot.smartboards.protocol.SmartBoardsMapAdapter;
import jab.spigot.smartboards.protocol.SmartBoardsClickAdapter;
import jab.spigot.smartboards.utils.SmartBoardSearch;
import jab.spigot.smartboards.utils.UVUtil;
import net.minecraft.server.v1_13_R2.PacketPlayOutMap;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * TODO: Document.
 *
 * @author Josh
 */
public class SmartBoardThread implements Runnable {

  public static final String THREAD_NAME = "SmartBoard Thread";
  private static volatile long SLEEP_TIME = 25L;

  /** Store all maps to the thread. */
  private final Map<Integer, SmartBoard> mapBoards;

  /** Key: Mini-Map ID, Value: Board using the ID. */
  private final Map<Integer, SmartBoard> mapBoardIds;

  /** This is a list of pre-approved packets registered by smartboards. */
  private final List<PacketPlayOutMap> listApprovedPackets;

  private final List<SmartBoard> listFlaggedBoards;

  private SmartBoard[] boardsToLoop;

  /** Flag to stop the thread on the next tick. */
  private volatile boolean stopped;

  private volatile boolean started;

  public final Object lockPackets = new Object();
  public final Object lockBoards = new Object();

  private SmartBoardsMapAdapter smartSmartBoardsMapAdapter;
  private SmartBoardsClickAdapter smartBoardsClickAdapter;

  private Thread thread;

  /** Main constructor. */
  SmartBoardThread() {
    this.mapBoards = new HashMap<>();
    this.mapBoardIds = new HashMap<>();
    this.listApprovedPackets = new ArrayList<>();
    this.listFlaggedBoards = new ArrayList<>();
    this.boardsToLoop = new SmartBoard[0];
    this.smartSmartBoardsMapAdapter = new SmartBoardsMapAdapter(this, PluginSmartBoards.instance);
    this.smartBoardsClickAdapter = new SmartBoardsClickAdapter(this, PluginSmartBoards.instance);
  }

  @Override
  public void run() {
    while (!stopped) {
      try {
        SmartBoard[] boards = getLoopBoards();
        if (boards.length > 0) {
          updateBoards(boards);
          renderBoards(boards);
        }
      } catch (Exception e) {
        System.out.println("An exception has occurred in the SmartBoard thread.");
        e.printStackTrace();
      }
      try {
        Thread.sleep(SLEEP_TIME);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  private void updateBoards(@NotNull SmartBoard[] boards) {
    synchronized (lockBoards) {
      // Update the MapBoards that are set to update.
      for (SmartBoard board : boards) {
        if (board.canUpdate()) {
          try {
            board.update();
          } catch (Exception e) {
            System.err.println(
                "The SmartBoard "
                    + board.getClass().getSimpleName()
                    + "(ID: "
                    + board.getId()
                    + ") has encountered an uncaught exception and has been disabled."
                    + "(Update)");
            e.printStackTrace(System.out);
            // Add the board to be removed.
            listFlaggedBoards.add(board);
          }
        }
      }
    }
    removeFlaggedBoards();
  }

  private void renderBoards(@NotNull SmartBoard[] boards) {
    synchronized (lockBoards) {
      // Go through each board that is listed as dirty and render them to their MapImage
      // caches.
      for (SmartBoard board : boards) {
        if (board.isDirty()) {
          try {
            // Render the board.
            board.render();
            // Let the thread know that this board is now drawn and doesn't need to be redrawn.
            board.setDirty(false);
            // Go through the rendered mini-maps and send them out to players nearby.
            board.dispatch();
          } catch (Exception e) {
            System.err.println(
                "The SmartBoard "
                    + board.getClass().getSimpleName()
                    + "(ID: "
                    + board.getId()
                    + ") has encountered an uncaught exception and has been disabled."
                    + "(Render)");
            e.printStackTrace(System.out);
            // Add the board to be removed.
            listFlaggedBoards.add(board);
          }
        }
      }
    }
    removeFlaggedBoards();
  }

  /**
   * @param player The Player to test.
   * @param maxDistance The maximum distance in blocks from the player to search for Boards.
   * @return Returns a BoardSearch result if the Player is looking at a Board within the maximum
   *     distance given. If the player is not looking at a board, null is returned.
   */
  @Nullable
  public SmartBoardSearch getBoardAndUVLookedAt(@NotNull Player player, int maxDistance) {
    // Attempt to grab any boards that the player is looking at within 12 blocks.
    List<SmartBoard> boards = getBoardsLookedAt(player, maxDistance);
    // No boards were looked at to interact with.
    if (boards == null) return null;
    // Attempt to grab the first board that is directly clicked on.
    double[] uv = null;
    SmartBoard boardClicked = null;
    for (SmartBoard board : boards) {
      if (!board.canClick()) continue;
      // If the player clicked on a board.
      uv = UVUtil.calculateUV(board, player);
      if (uv != null) {
        boardClicked = board;
        break;
      }
    }
    // No boards were actually looked at directly.
    if (uv == null) return null;
    return new SmartBoardSearch(boardClicked, uv);
  }

  @Nullable
  public List<SmartBoard> getBoardsLookedAt(@NotNull Player player, int maxDistance) {
    List<SmartBoard> boards;
    synchronized (lockBoards) {
      boards = new ArrayList<>(getBoards());
    }
    boards.removeIf(board -> !board.isLookingAt(player, maxDistance));
    return boards;
  }

  private void removeFlaggedBoards() {
    synchronized (listFlaggedBoards) {
      // Remove any boards added to the removal list.
      if (!listFlaggedBoards.isEmpty()) {
        for (SmartBoard board : listFlaggedBoards) {
          removeBoard(board);
        }
        listFlaggedBoards.clear();
      }
    }
  }

  public void addBoard(@NotNull SmartBoard board) {
    if (!isRegistered(board)) {
      // Place the board in the registrar map.
      mapBoards.put(board.getId(), board);
      // Add the board to the loop array to be updated.
      SmartBoard[] boardsToLoopNew = new SmartBoard[boardsToLoop.length + 1];
      System.arraycopy(boardsToLoop, 0, boardsToLoopNew, 0, boardsToLoop.length);
      boardsToLoopNew[boardsToLoop.length] = board;
      boardsToLoop = boardsToLoopNew;
      // Add any registered map IDs to be interpreted by the packet checker.
      updateBoardIds(board);
    }
  }

  public void removeBoard(@NotNull SmartBoard board) {
    if (isRegistered(board)) {
      synchronized (lockBoards) {
        // Remove the board from the registrar map.
        mapBoards.remove(board.getId());
        // Remove the board from the loop array.
        SmartBoard[] boardsToLoopNew = new SmartBoard[boardsToLoop.length - 1];
        int index = 0;
        for (SmartBoard boardNext : boardsToLoop) {
          if (!boardNext.equals(board)) {
            boardsToLoopNew[index++] = boardNext;
          }
        }
        boardsToLoop = boardsToLoopNew;
      }
      // Remove any registered map IDs from the board.
      removeBoardIds(board);
    }
  }

  public void updateBoardIds(@NotNull SmartBoard board) {
    // Remove the board's previous ids.
    synchronized (mapBoardIds) {
      for (int key : new HashSet<>(mapBoardIds.keySet())) {
        SmartBoard boardNext = mapBoardIds.get(key);
        if (boardNext == null || boardNext.equals(board)) {
          mapBoardIds.remove(key);
        }
      }
    }
    // Go through and add all ids to the map.
    int[] ids = board.getMapIds();
    if (ids != null && ids.length > 0) {
      // Check the ID map.
      synchronized (mapBoardIds) {
        for (int id : board.getMapIds()) {
          mapBoardIds.put(id, board);
        }
      }
    }
  }

  public void removeBoardIds(@NotNull SmartBoard board) {
    int[] ids = board.getMapIds();
    if (ids != null && ids.length > 0) {
      // Check the ID map.
      synchronized (mapBoardIds) {
        for (int id : board.getMapIds()) {
          mapBoardIds.remove(id);
        }
      }
    }
  }

  public void start() {
    if (!started) {
      this.mapBoards.clear();
      this.mapBoardIds.clear();
      this.listFlaggedBoards.clear();
      this.listApprovedPackets.clear();
      this.boardsToLoop = new SmartBoard[0];
      ProtocolManager protocolManager = PluginSmartBoards.protocolManager;
      protocolManager.addPacketListener(smartSmartBoardsMapAdapter);
      protocolManager.addPacketListener(smartBoardsClickAdapter);
    }
    this.started = true;
    this.stopped = false;

    thread = new Thread(this, THREAD_NAME);
    thread.start();
  }

  public void pause() {
    this.stopped = true;
  }

  public void resume() {
    this.stopped = false;
    if (thread == null) {
      thread = new Thread(this, THREAD_NAME);
    }
    if (!thread.isAlive() || thread.isInterrupted()) {
      thread.start();
    }
  }

  public void stop() {
    this.started = false;
    this.stopped = true;
    ProtocolManager protocolManager = PluginSmartBoards.protocolManager;
    protocolManager.removePacketListener(smartSmartBoardsMapAdapter);
    protocolManager.removePacketListener(smartBoardsClickAdapter);
  }

  private SmartBoard[] getLoopBoards() {
    return this.boardsToLoop;
  }

  @NotNull
  public Collection<SmartBoard> getBoards() {
    return mapBoards.values();
  }

  public boolean isRegistered(@NotNull SmartBoard board) {
    return mapBoards.containsKey(board.getId());
  }

  @NotNull
  public Map<Integer, SmartBoard> getRegisteredMapIds() {
    return this.mapBoardIds;
  }

  @NotNull
  public List<PacketPlayOutMap> getRegisteredMapPackets() {
    return this.listApprovedPackets;
  }
}