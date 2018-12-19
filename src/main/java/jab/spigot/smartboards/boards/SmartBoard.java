package jab.spigot.smartboards.boards;

import jab.spigot.smartboards.enums.BoardDirection;
import jab.spigot.smartboards.events.SmartBoardClickEvent;
import jab.spigot.smartboards.utils.BoardProfile;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** @author Josh */
public interface SmartBoard {

  /** @return Returns the next unique ID for new smartboards. */
  default int getNewId() {
    return _id_counter.NEXT_ID++;
  }

  /** NOTE: This method is only fired for async smartboards. */
  void update();

  /** NOTE: This method is only fired for async smartboards. */
  void render();

  /** This is fired when a player clicks on the smartboard. */
  void onClick(SmartBoardClickEvent eventToPass);

  /**
   * @param player The player to test.
   * @param maxDistance The maximum distance (in blocks) a ray can be cast.
   * @return Returns true if the smartboard is looked at by the player.
   */
  boolean isLookingAt(Player player, int maxDistance);

  /** NOTE: This method is only fired for async smartboards. */
  boolean canUpdate();

  /** NOTE: This method is only fired for async smartboards. */
  boolean isDirty();

  /** NOTE: This method is only fired for async smartboards. */
  void setDirty(boolean flag);

  /** NOTE: This method is only fired for async smartboards. */
  void dispatch();

  /** @return Returns the profile used to define attributes for the smartboard. */
  BoardProfile getProfile();

  /**
   * @return Returns the bottom-left location of the smartboard. (Relative to the direction the
   *     board faces)
   */
  Location getLocation();

  /** @return Returns the direction that the smartboard is facing. */
  BoardDirection getDirection();

  int[] getMapIds();

  /** @return Returns the width of the smartboard in blocks. */
  int getWidth();

  /** @return Returns the height of the smartboard in blocks. */
  int getHeight();

  /** @return Returns the ID assigned to the smartboard. */
  int getId();

  /** @return Returns true if the smartboard should be registered to the smartboard thread. */
  boolean isAsync();

  /** @return Returns true if the smartboard can be clicked. */
  boolean canClick();

  /**
   * Sets the ability for the board to be clickable.
   *
   * @param flag The flag to set.
   */
  void setCanClick(boolean flag);

  /**
   * This is the formula for one-dimensional arrays composed of unique indexes of 2-dimensional
   * coordinates: <br>
   * index = (y + width) + (width - 1) + x
   *
   * @param x The x coordinate relative to the board's left side.
   * @param y The y coordinate relative to the board's top side.
   * @return Returns the array index for the given coordinates.
   */
  static int getIndex(int x, int y, int width) {
    return (y * width) + (width - 1) - x;
  }
}

class _id_counter {
  static int NEXT_ID = 0;
}