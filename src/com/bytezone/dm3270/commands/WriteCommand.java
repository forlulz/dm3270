package com.bytezone.dm3270.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bytezone.dm3270.application.Utility;
import com.bytezone.dm3270.assistant.BatchJobListener;
import com.bytezone.dm3270.display.Cursor;
import com.bytezone.dm3270.display.Screen;
import com.bytezone.dm3270.orders.Order;
import com.bytezone.dm3270.orders.TextOrder;

public class WriteCommand extends Command
{
  private static final Pattern jobSubmittedPattern =
      Pattern.compile ("^JOB ([A-Z0-9]{1,9})\\(JOB(\\d{5})\\) SUBMITTED");

  private static final Pattern jobCompletedPattern =
      Pattern.compile ("(^\\d\\d(?:\\.\\d\\d){2}) JOB(\\d{5})"
          + " \\$HASP\\d+ ([A-Z0-9]+) .* MAXCC=(\\d+).*");

  private static final Pattern jobFailedPattern =
      Pattern.compile ("(^\\d\\d(?:\\.\\d\\d){2}) JOB(\\d{5})"
          + " \\$HASP\\d+ ([A-Z0-9]+) .* JCL ERROR.*");

  private final boolean eraseWrite;
  private final WriteControlCharacter writeControlCharacter;
  private final List<Order> orders = new ArrayList<Order> ();

  // this whole SystemMessage idea needs to be rewritten
  private final byte[] systemMessage1 =
      { //
        Order.SET_BUFFER_ADDRESS, Order.START_FIELD, 0x00, Order.START_FIELD,
        Order.SET_BUFFER_ADDRESS, Order.INSERT_CURSOR };

  private final byte[] systemMessage2 =
      { //
        Order.SET_BUFFER_ADDRESS, Order.START_FIELD, Order.SET_BUFFER_ADDRESS,
        Order.START_FIELD, 0x00, Order.START_FIELD, Order.SET_BUFFER_ADDRESS,
        Order.START_FIELD, 0x00, Order.START_FIELD, Order.INSERT_CURSOR };

  private final byte[] systemMessage3 =
      { //
        Order.SET_BUFFER_ADDRESS, Order.START_FIELD, Order.SET_BUFFER_ADDRESS,
        Order.START_FIELD, 0x00, Order.START_FIELD, Order.SET_BUFFER_ADDRESS,
        Order.INSERT_CURSOR };

  private final byte[] systemMessage4 =
      { //
        Order.SET_BUFFER_ADDRESS, Order.START_FIELD, Order.SET_BUFFER_ADDRESS,
        Order.START_FIELD, 0x00, Order.START_FIELD, Order.SET_BUFFER_ADDRESS,
        Order.START_FIELD, 0x00, Order.START_FIELD, Order.SET_BUFFER_ADDRESS,
        Order.START_FIELD, 0x00, Order.START_FIELD, Order.INSERT_CURSOR };

  private final byte[] systemMessage5 =
      { //
        Order.SET_BUFFER_ADDRESS, Order.START_FIELD, 0x00, Order.START_FIELD,
        Order.SET_BUFFER_ADDRESS, Order.START_FIELD, 0x00, Order.START_FIELD,
        Order.INSERT_CURSOR };

  public WriteCommand (byte[] buffer, int offset, int length, Screen screen,
      boolean erase)
  {
    super (buffer, offset, length, screen);

    this.eraseWrite = erase;

    // ?????
    // I think that this command (when sourced from a WSF command) has an address
    // field after the WCC. Perhaps the constructor could be passed the WCC and
    // starting address.

    if (length > 1)
      writeControlCharacter = new WriteControlCharacter (buffer[offset + 1]);
    else
      writeControlCharacter = null;

    int ptr = offset + 2;
    Order previousOrder = null;

    int max = offset + length;
    while (ptr < max)
    {
      Order order = Order.getOrder (buffer, ptr, max);

      if (order.rejected ())
        break;

      if (previousOrder != null && previousOrder.matches (order))
        previousOrder.incrementDuplicates ();
      else
      {
        orders.add (order);
        previousOrder = order;
      }

      ptr += order.size ();
    }
  }

  // Used by MainframeStage.createCommand() when building a screen
  public WriteCommand (WriteControlCharacter wcc, boolean erase, List<Order> orders)
  {
    super (null);

    this.writeControlCharacter = wcc;
    this.eraseWrite = erase;
    this.orders.addAll (orders);

    // create new data buffer
    int length = 2;// command + WCC
    for (Order order : orders)
      length += order.size ();
    data = new byte[length];

    int ptr = 0;

    // add the command and WCC
    data[ptr++] = erase ? ERASE_WRITE_F5 : WRITE_F1;
    data[ptr++] = wcc.getValue ();

    // add each order
    for (Order order : orders)
      ptr = order.pack (data, ptr);

    assert ptr == data.length;
  }

  @Override
  public void process ()
  {
    Cursor cursor = screen.getScreenCursor ();
    int cursorLocation = cursor.getLocation ();
    screen.lockKeyboard ("Inhibit");
    boolean screenDrawRequired = false;

    if (eraseWrite)
      screen.clearScreen ();// resets pen

    if (orders.size () > 0)
    {
      for (Order order : orders)
        order.process (screen);// modifies pen

      cursor.moveTo (cursorLocation);
      screen.buildFields (writeControlCharacter);
      screenDrawRequired = true;
    }

    if (writeControlCharacter != null)
    {
      writeControlCharacter.process (screen);// may unlock the keyboard
      screen.checkRecording ();
    }

    if (!screen.isKeyboardLocked () && screen.getFieldManager ().size () > 0)
    {
      reply = screen.getPluginsStage ().processPluginAuto ();// check for suppressDisplay
    }

    if (screenDrawRequired)
      screen.draw ();

    if (orders.size () > 0)
      checkSystemMessage ();// check screen for jobs submitted or finished
  }

  private void checkSystemMessage ()
  {
    if (screen != null) // mainframe mode has no screen
      addBatchJobListener (screen.getAssistantStage ());// fix this

    if (eraseWrite && orders.size () == 8)
    {
      if (checkOrders (systemMessage3))
        checkSystemMessage (Utility.getString (orders.get (4).getBuffer ()));
      return;
    }

    if (eraseWrite && orders.size () == 11)
    {
      if (checkOrders (systemMessage2))
        checkSystemMessage (Utility.getString (orders.get (4).getBuffer ()));
      return;
    }

    if (eraseWrite && orders.size () == 15)
    {
      if (checkOrders (systemMessage4))
      {
        checkSystemMessage (Utility.getString (orders.get (4).getBuffer ()));
        checkSystemMessage (Utility.getString (orders.get (8).getBuffer ()));
      }
      return;
    }

    if (!eraseWrite && orders.size () == 6)
    {
      if (checkOrders (systemMessage1))
        checkSystemMessage (Utility.getString (orders.get (2).getBuffer ()));
      return;
    }

    if (!eraseWrite && orders.size () == 9)
    {
      if (checkOrders (systemMessage5))
        checkSystemMessage (Utility.getString (orders.get (2).getBuffer ()));
      return;
    }

    if (orders.size () < 30 && false)
    {
      System.out.printf ("Orders: %d%n", orders.size ());
      System.out.printf ("Erase : %s%n", eraseWrite);
      for (Order order : orders)
        System.out.println (order);
      System.out.println ("-------------------------------");
    }
  }

  private boolean checkOrders (byte[] systemMessage)
  {
    int ptr = 0;
    for (Order order : orders)
    {
      byte reqType = systemMessage[ptr++];
      if (reqType != 0 && reqType != order.getType ())
        return false;
    }
    return true;
  }

  private void checkSystemMessage (String systemMessageText)
  {
    Matcher matcher = jobSubmittedPattern.matcher (systemMessageText);
    if (matcher.matches ())
    {
      fireBatchJobSubmitted (Integer.parseInt (matcher.group (2)), matcher.group (1));
      return;
    }

    matcher = jobCompletedPattern.matcher (systemMessageText);
    if (matcher.matches ())
    {
      int jobNumber = Integer.parseInt (matcher.group (2));
      int conditionCode = Integer.parseInt (matcher.group (4));
      fireBatchJobEnded (jobNumber, matcher.group (3), matcher.group (1), conditionCode);
      return;
    }

    matcher = jobFailedPattern.matcher (systemMessageText);
    if (matcher.matches ())
    {
      int jobNumber = Integer.parseInt (matcher.group (2));
      String jobName = matcher.group (3);
      String time = matcher.group (1);
      fireBatchJobFailed (jobNumber, jobName, time);
      return;
    }
  }

  // Used by Session.checkServerName() when searching for the server's name
  public List<Order> getOrdersList ()
  {
    return orders;
  }

  @Override
  public String getName ()
  {
    return eraseWrite ? "Erase Write" : "Write";
  }

  @Override
  public String toString ()
  {
    StringBuilder text = new StringBuilder ();
    text.append (getName ());
    text.append ("\nWCC : " + writeControlCharacter);

    // if the list begins with a TextOrder then tab out the missing columns
    if (orders.size () > 0 && orders.get (0) instanceof TextOrder)
      text.append (String.format ("%40s", ""));

    for (Order order : orders)
    {
      String fmt = (order instanceof TextOrder) ? "%s" : "%n%-40s";
      text.append (String.format (fmt, order));
    }

    return text.toString ();
  }

  // ---------------------------------------------------------------------------------//
  // BatchJobListener
  // ---------------------------------------------------------------------------------//

  private final Set<BatchJobListener> batchJobListeners = new HashSet<> ();

  void fireBatchJobSubmitted (int jobNumber, String jobName)
  {
    for (BatchJobListener listener : batchJobListeners)
      listener.batchJobSubmitted (jobNumber, jobName);
  }

  void fireBatchJobEnded (int jobNumber, String jobName, String time, int conditionCode)
  {
    for (BatchJobListener listener : batchJobListeners)
      listener.batchJobEnded (jobNumber, jobName, time, conditionCode);
  }

  void fireBatchJobFailed (int jobNumber, String jobName, String time)
  {
    for (BatchJobListener listener : batchJobListeners)
      listener.batchJobFailed (jobNumber, jobName, time);
  }

  public void addBatchJobListener (BatchJobListener listener)
  {
    batchJobListeners.add (listener);
  }

  public void removeBatchJobListener (BatchJobListener listener)
  {
    batchJobListeners.remove (listener);
  }
}