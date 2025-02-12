#!/usr/bin/env python
# Copyright 2012 Cloudera Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Impala's shell
import cmd
import errno
import getpass
import os
import prettytable
import random
import re
import shlex
import signal
import socket
import sqlparse
import sys
import textwrap
import time

from impala_client import (ImpalaClient, DisconnectedException, QueryStateException,
                           RPCException, TApplicationException)
from impala_shell_config_defaults import impala_shell_defaults
from option_parser import get_option_parser, get_config_from_file
from shell_output import DelimitedOutputFormatter, OutputStream, PrettyOutputFormatter
from shell_output import OverwritingStdErrOutputStream
from subprocess import call
from thrift.Thrift import TException

VERSION_FORMAT = "Impala Shell v%(version)s (%(git_hash)s) built on %(build_date)s"
VERSION_STRING = "build version not available"
HISTORY_LENGTH = 100

# Tarball / packaging build makes impala_build_version available
try:
  from impala_build_version import get_git_hash, get_build_date, get_version
  VERSION_STRING = VERSION_FORMAT % {'version': get_version(),
                                     'git_hash': get_git_hash()[:7],
                                     'build_date': get_build_date()}
except Exception:
  pass

class CmdStatus:
  """Values indicate the execution status of a command to the cmd shell driver module
  SUCCESS and ERROR continue running the shell and ABORT exits the shell
  Since SUCCESS == None, successful commands do not need to explicitly return
  anything on completion
  """
  SUCCESS = None
  ABORT = True
  ERROR = False

class ImpalaPrettyTable(prettytable.PrettyTable):
  """Patched version of PrettyTable that TODO"""
  def _unicode(self, value):
    if not isinstance(value, basestring):
      value = str(value)
    if not isinstance(value, unicode):
      # If a value cannot be encoded, replace it with a placeholder.
      value = unicode(value, self.encoding, "replace")
    return value

class ImpalaShell(cmd.Cmd):
  """ Simple Impala Shell.

  Basic usage: type connect <host:port> to connect to an impalad
  Then issue queries or other commands. Tab-completion should show the set of
  available commands.
  Methods that implement shell commands return a boolean tuple (stop, status)
  stop is a flag the command loop uses to continue/discontinue the prompt.
  Status tells the caller that the command completed successfully.
  """

  # If not connected to an impalad, the server version is unknown.
  UNKNOWN_SERVER_VERSION = "Not Connected"
  DISCONNECTED_PROMPT = "[Not connected] > "
  # Error and warning that is printed by cancel_query
  CANCELLATION_ERROR = 'Cancelled'
  # Message to display in shell when cancelling a query
  CANCELLATION_MESSAGE = ' Cancelling Query'
  # Commands are terminated with the following delimiter.
  CMD_DELIM = ';'
  DEFAULT_DB = 'default'
  # Regex applied to all tokens of a query to detect the query type.
  INSERT_REGEX = re.compile("^insert$", re.I)
  # Seperator for queries in the history file.
  HISTORY_FILE_QUERY_DELIM = '_IMP_DELIM_'

  VALID_SHELL_OPTIONS = {
    'LIVE_PROGRESS' : (lambda x: x in ("true", "TRUE", "True", "1"), "print_progress"),
    'LIVE_SUMMARY' : (lambda x: x in ("true", "TRUE", "True", "1"), "print_summary")
  }

  # Minimum time in seconds between two calls to get the exec summary.
  PROGRESS_UPDATE_INTERVAL = 1.0

  def __init__(self, options):
    cmd.Cmd.__init__(self)
    self.is_alive = True

    self.impalad = None
    self.use_kerberos = options.use_kerberos
    self.kerberos_service_name = options.kerberos_service_name
    self.use_ssl = options.ssl
    self.ca_cert = options.ca_cert
    self.user = options.user
    self.ldap_password = None;
    self.use_ldap = options.use_ldap

    self.verbose = options.verbose
    self.prompt = ImpalaShell.DISCONNECTED_PROMPT
    self.server_version = ImpalaShell.UNKNOWN_SERVER_VERSION

    self.refresh_after_connect = options.refresh_after_connect
    self.current_db = options.default_db
    self.history_file = os.path.expanduser("~/.impalahistory")
    # Stores the state of user input until a delimiter is seen.
    self.partial_cmd = str()
    # Stores the old prompt while the user input is incomplete.
    self.cached_prompt = str()

    self.show_profiles = options.show_profiles

    # Output formatting flags/options
    self.output_file = options.output_file
    self.output_delimiter = options.output_delimiter
    self.write_delimited = options.write_delimited
    self.print_header = options.print_header

    self.progress_stream = OverwritingStdErrOutputStream()

    self.set_query_options = {}

    self._populate_command_list()

    self.imp_client = None;

    # Tracks query handle of the last query executed. Used by the 'profile' command.
    self.last_query_handle = None;
    self.query_handle_closed = None

    self.print_summary = options.print_summary
    self.print_progress = options.print_progress

    # Due to a readline bug in centos/rhel7, importing it causes control characters to be
    # printed. This breaks any scripting against the shell in non-interactive mode. Since
    # the non-interactive mode does not need readline - do not import it.
    if options.query or options.query_file:
      self.interactive = False
      self._disable_readline()
    else:
      self.interactive = True
      try:
        self.readline = __import__('readline')
        self.readline.set_history_length(HISTORY_LENGTH)
      except ImportError:
        self._disable_readline()

    if options.impalad is not None:
      self.do_connect(options.impalad)

    # We handle Ctrl-C ourselves, using an Event object to signal cancellation
    # requests between the handler and the main shell thread.
    signal.signal(signal.SIGINT, self._signal_handler)

  def _populate_command_list(self):
    """Populate a list of commands in the shell.

    Each command has its own method of the form do_<command>, and can be extracted by
    introspecting the class directory.
    """
    # Slice the command method name to get the name of the command.
    self.commands = [cmd[3:] for cmd in dir(self.__class__) if cmd.startswith('do_')]

  def _disable_readline(self):
    """Disables the readline module.

    The readline module is responsible for keeping track of command history.
    """
    self.readline = None

  def _print_options(self, default_options, set_options):
    # Prints the current query options
    # with default values distinguished from set values by brackets [], followed by
    # shell-local options.
    if not default_options and not set_options:
      print '\tNo options available.'
    else:
      for k in sorted(default_options.keys()):
        if k in set_options.keys() and set_options[k] != default_options[k]:
          print '\n'.join(["\t%s: %s" % (k, set_options[k])])
        else:
          print '\n'.join(["\t%s: [%s]" % (k, default_options[k])])
    self._print_shell_options()

  def _print_shell_options(self):
    """Prints shell options, which are local and independent of query options."""
    print "\nShell Options"
    for x in self.VALID_SHELL_OPTIONS:
      print "\t%s: %s" % (x, self.__dict__[self.VALID_SHELL_OPTIONS[x][1]])

  def do_shell(self, args):
    """Run a command on the shell
    Usage: shell <cmd>
           ! <cmd>

    """
    try:
      start_time = time.time()
      os.system(args)
      self._print_if_verbose("--------\nExecuted in %2.2fs" % (time.time() - start_time))
    except Exception, e:
      print_to_stderr('Error running command : %s' % e)
      return CmdStatus.ERROR

  def sanitise_input(self, args):
    """Convert the command to lower case, so it's recognized"""
    # A command terminated by a semi-colon is legal. Check for the trailing
    # semi-colons and strip them from the end of the command.
    args = args.strip()
    tokens = args.split(' ')
    if not self.interactive:
      tokens[0] = tokens[0].lower()
      # Strip all the non-interactive commands of the delimiter.
      return ' '.join(tokens).rstrip(ImpalaShell.CMD_DELIM)
    # The first token is converted into lower case to route it to the
    # appropriate command handler. This only applies to the first line of user input.
    # Modifying tokens in subsequent lines may change the semantics of the command,
    # so do not modify the text.
    if not self.partial_cmd:
      # The first token is the command.
      # If it's EOF, call do_quit()
      if tokens[0] == 'EOF':
        return 'quit'
      else:
        tokens[0] = tokens[0].lower()
    elif tokens[0] == "EOF":
      # If a command is in progress and the user hits a Ctrl-D, clear its state
      # and reset the prompt.
      self.prompt = self.cached_prompt
      self.partial_cmd = str()
      # The print statement makes the new prompt appear in a new line.
      # Also print an extra newline to indicate that the current command has
      # been cancelled.
      print '\n'
      return str()
    args = self._check_for_command_completion(' '.join(tokens).strip())
    return args.rstrip(ImpalaShell.CMD_DELIM)

  def _shlex_split(self, line):
    """Reimplement shlex.split() so that escaped single quotes
    are actually escaped. shlex.split() only escapes double quotes
    by default. This method will throw a ValueError if an open
    quotation (either single or double) is found.
    """
    my_split = shlex.shlex(line, posix=True)
    my_split.escapedquotes = '"\''
    my_split.whitespace_split = True
    my_split.commenters = ''
    return list(my_split)

  def _cmd_ends_with_delim(self, line):
    """Check if the input command ends with a command delimiter.

    A command ending with the delimiter and containing an open quotation character is
    not considered terminated. If no open quotation is found, it's considered
    terminated.
    """
    if line.endswith(ImpalaShell.CMD_DELIM):
      try:
        # Look for an open quotation in the entire command, and not just the
        # current line.
        if self.partial_cmd: line = '%s %s' % (self.partial_cmd, line)
        self._shlex_split(line)
        return True
      # If the command ends with a delimiter, check if it has an open quotation.
      # shlex in self._split() throws a ValueError iff an open quotation is found.
      # A quotation can either be a single quote or a double quote.
      except ValueError:
        pass

    # This checks to see if there are any backslashed quotes
    # outside of quotes, since backslashed quotes
    # outside of single or double quotes should not be escaped.
    # Ex. 'abc\'xyz' -> closed because \' is escaped
    #     \'abcxyz   -> open because \' is not escaped
    #     \'abcxyz'  -> closed
    # Iterate through the line and switch the state if a single or double quote is found
    # and ignore escaped single and double quotes if the line is considered open (meaning
    # a previous single or double quote has not been closed yet)
      state_closed = True;
      opener = None;
      for i, char in enumerate(line):
        if state_closed and (char in ['\'', '\"']):
          state_closed = False
          opener = char
        elif not state_closed and opener == char:
          if line[i - 1] != '\\':
            state_closed = True
            opener = None;

      return state_closed

    return False

  def _check_for_command_completion(self, cmd):
    """Check for a delimiter at the end of user input.

    The end of the user input is scanned for a legal delimiter.
    If a delimiter is not found:
      - Input is not send to onecmd()
        - onecmd() is a method in Cmd which routes the user input to the
          appropriate method. An empty string results in a no-op.
      - Input is removed from history.
      - Input is appended to partial_cmd
    If a delimiter is found:
      - The contents of partial_cmd are put in history, as they represent
        a completed command.
      - The contents are passed to the appropriate method for execution.
      - partial_cmd is reset to an empty string.
    """
    if self.readline: current_history_len = self.readline.get_current_history_length()
    # Input is incomplete, store the contents and do nothing.
    if not self._cmd_ends_with_delim(cmd):
      # The user input is incomplete, change the prompt to reflect this.
      if not self.partial_cmd and cmd:
        self.cached_prompt = self.prompt
        self.prompt = '> '.rjust(len(self.cached_prompt))

      # partial_cmd is already populated, add the current input after a newline.
      if self.partial_cmd and cmd:
        self.partial_cmd = "%s\n%s" % (self.partial_cmd, cmd)
      else:
        # If the input string is empty or partial_cmd is empty.
        self.partial_cmd = "%s%s" % (self.partial_cmd, cmd)
      # Remove the most recent item from history if:
      #   -- The current state of user input in incomplete.
      #   -- The most recent user input is not an empty string
      if self.readline and current_history_len > 0 and cmd:
        self.readline.remove_history_item(current_history_len - 1)
      # An empty string results in a no-op. Look at emptyline()
      return str()
    elif self.partial_cmd:  # input ends with a delimiter and partial_cmd is not empty
      if cmd != ImpalaShell.CMD_DELIM:
        completed_cmd = "%s\n%s" % (self.partial_cmd, cmd)
      else:
        completed_cmd = "%s%s" % (self.partial_cmd, cmd)
      # Reset partial_cmd to an empty string
      self.partial_cmd = str()
      # Replace the most recent history item with the completed command.
      completed_cmd = sqlparse.format(completed_cmd)
      if self.readline and current_history_len > 0:
        self.readline.replace_history_item(current_history_len - 1,
            completed_cmd.encode('utf-8'))
      # Revert the prompt to its earlier state
      self.prompt = self.cached_prompt
    else:  # Input has a delimiter and partial_cmd is empty
      completed_cmd = sqlparse.format(cmd)
    return completed_cmd

  def _signal_handler(self, signal, frame):
    """Handles query cancellation on a Ctrl+C event"""
    if self.last_query_handle is None or self.query_handle_closed:
      return
    # Create a new connection to the impalad and cancel the query.
    try:
      self.query_handle_closed = True
      print_to_stderr(ImpalaShell.CANCELLATION_MESSAGE)
      new_imp_client = ImpalaClient(self.impalad)
      new_imp_client.connect()
      new_imp_client.cancel_query(self.last_query_handle, False)
      self.imp_client.close_query(self.last_query_handle)
      self._validate_database()
    except Exception, e:
      print_to_stderr("Failed to reconnect and close: %s" % str(e))
      # TODO: Add a retry here

  def precmd(self, args):
    args = self.sanitise_input(args)
    if not args: return args
    # Split args using sqlparse. If there are multiple queries present in user input,
    # the length of the returned query list will be greater than one.
    parsed_cmds = sqlparse.split(args)
    if len(parsed_cmds) > 1:
      # The last command needs a delimiter to be successfully executed.
      parsed_cmds[-1] += ImpalaShell.CMD_DELIM
      self.cmdqueue.extend(parsed_cmds)
      # If cmdqueue is populated, then commands are executed from the cmdqueue, and user
      # input is ignored. Send an empty string as the user input just to be safe.
      return str()
    try:
      self.imp_client.test_connection()
    except TException:
      print_to_stderr("Connection lost, reconnecting...")
      self._connect()
    return args.encode('utf-8')

  def postcmd(self, status, args):
    # status conveys to shell how the shell should continue execution
    # should always be a CmdStatus
    return status

  def do_summary(self, args):
    summary = None
    try:
      summary = self.imp_client.get_summary(self.last_query_handle)
    except RPCException:
      pass
    if summary is None:
      print_to_stderr("Could not retrieve summary for query.")
      return CmdStatus.ERROR
    if summary.nodes is None:
      print_to_stderr("Summary not available")
      return CmdStatus.SUCCESS
    output = []
    table = self._default_summary_table()
    self.imp_client.build_summary_table(summary, 0, False, 0, False, output)
    formatter = PrettyOutputFormatter(table)
    self.output_stream = OutputStream(formatter, filename=self.output_file)
    self.output_stream.write(output)

  def _handle_shell_options(self, token, value):
    try:
      handle = self.VALID_SHELL_OPTIONS[token]
      self.__dict__[handle[1]] = handle[0](value)
      return True
    except KeyError:
      return False

  def do_set(self, args):
    """Set or display query options.

    Display query options:
    Usage: SET
    Set query options:
    Usage: SET <option>=<value>

    """
    # TODO: Expand set to allow for setting more than just query options.
    if len(args) == 0:
      print "Query options (defaults shown in []):"
      self._print_options(self.imp_client.default_query_options, self.set_query_options);
      return CmdStatus.SUCCESS

    # Remove any extra spaces surrounding the tokens.
    # Allows queries that have spaces around the = sign.
    tokens = [arg.strip() for arg in args.split("=")]
    if len(tokens) != 2:
      print_to_stderr("Error: SET <option>=<value>")
      return CmdStatus.ERROR
    option_upper = tokens[0].upper()
    if not self._handle_shell_options(option_upper, tokens[1]):
      if option_upper not in self.imp_client.default_query_options.keys():
        print "Unknown query option: %s" % (tokens[0])
        print "Available query options, with their values (defaults shown in []):"
        self._print_options(self.imp_client.default_query_options, self.set_query_options)
        return CmdStatus.ERROR
      self.set_query_options[option_upper] = tokens[1]
    self._print_if_verbose('%s set to %s' % (option_upper, tokens[1]))

  def do_unset(self, args):
    """Unset a query option"""
    if len(args.split()) != 1:
      print 'Usage: unset <option>'
      return CmdStatus.ERROR
    option = args.upper()
    if self.set_query_options.get(option):
      print 'Unsetting %s' % option
      del self.set_query_options[option]
    else:
      print "No option called %s is set" % args

  def do_quit(self, args):
    """Quit the Impala shell"""
    self._print_if_verbose("Goodbye " + self.user)
    self.is_alive = False
    return CmdStatus.ABORT

  def do_exit(self, args):
    """Exit the impala shell"""
    return self.do_quit(args)

  def do_connect(self, args):
    """Connect to an Impalad instance:
    Usage: connect, defaults to the fqdn of the localhost and port 21000
           connect <hostname:port>
           connect <hostname>, defaults to port 21000

    """
    # Assume the user wants to connect to the local impalad if no connection string is
    # specified. Connecting to a kerberized impalad requires an fqdn as the host name.
    if self.use_ldap and self.ldap_password is None:
      self.ldap_password = getpass.getpass("LDAP password for %s: " % self.user)

    if not args: args = socket.getfqdn()
    tokens = args.split(" ")
    # validate the connection string.
    host_port = [val for val in tokens[0].split(':') if val.strip()]
    if (':' in tokens[0] and len(host_port) != 2):
      print_to_stderr("Connection string must either be empty, or of the form "
                      "<hostname[:port]>")
      return CmdStatus.ERROR
    elif len(host_port) == 1:
      host_port.append('21000')
    self.impalad = tuple(host_port)
    if self.imp_client: self.imp_client.close_connection()
    self.imp_client = ImpalaClient(self.impalad, self.use_kerberos,
                                   self.kerberos_service_name, self.use_ssl,
                                   self.ca_cert, self.user, self.ldap_password,
                                   self.use_ldap)
    self._connect()
    # If the connection fails and the Kerberos has not been enabled,
    # check for a valid kerberos ticket and retry the connection
    # with kerberos enabled.
    if not self.imp_client.connected and not self.use_kerberos:
      try:
        if call(["klist", "-s"]) == 0:
          print_to_stderr(("Kerberos ticket found in the credentials cache, retrying "
                           "the connection with a secure transport."))
          self.imp_client.use_kerberos = True
          self.imp_client.use_ldap = False
          self.imp_client.ldap_password = None
          self._connect()
      except OSError, e:
        pass

    if self.imp_client.connected:
      self._print_if_verbose('Connected to %s:%s' % self.impalad)
      self._print_if_verbose('Server version: %s' % self.server_version)
      self.prompt = "[%s:%s] > " % self.impalad
      if self.refresh_after_connect:
        self.cmdqueue.append('invalidate metadata' + ImpalaShell.CMD_DELIM)
        print_to_stderr("Invalidating Metadata")
      self._validate_database()
    try:
      self.imp_client.build_default_query_options_dict()
    except RPCException, e:
      print_to_stderr(e)
    # In the case that we lost connection while a command was being entered,
    # we may have a dangling command, clear partial_cmd
    self.partial_cmd = str()
    # Check if any of query options set by the user are inconsistent
    # with the impalad being connected to
    for set_option in self.set_query_options.keys():
      if set_option not in set(self.imp_client.default_query_options.keys()):
        print ('%s is not supported for the impalad being '
               'connected to, ignoring.' % set_option)
        del self.set_query_options[set_option]

  def _connect(self):
    try:
      server_version = self.imp_client.connect()
      if server_version:
        self.server_version = server_version
    except TApplicationException:
      # We get a TApplicationException if the transport is valid,
      # but the RPC does not exist.
      print_to_stderr("Error: Unable to communicate with impalad service. This "
               "service may not be an impalad instance. Check host:port and try again.")
      self.imp_client.close_connection()
      raise
    except ImportError:
      print_to_stderr("Unable to import the python 'ssl' module. It is"
      " required for an SSL-secured connection.")
      sys.exit(1)
    except socket.error, (code, e):
      # if the socket was interrupted, reconnect the connection with the client
      if code == errno.EINTR:
        self._reconnect_cancellation()
      else:
        print_to_stderr("Socket error %s: %s" % (code, e))
        self.prompt = self.DISCONNECTED_PROMPT
    except Exception, e:
      print_to_stderr("Error connecting: %s, %s" % (type(e).__name__, e))
      # If a connection to another impalad failed while already connected
      # reset the prompt to disconnected.
      self.server_version = self.UNKNOWN_SERVER_VERSION
      self.prompt = self.DISCONNECTED_PROMPT

  def _reconnect_cancellation(self):
    self._connect()
    self._validate_database()

  def _validate_database(self):
    if self.current_db:
      self.current_db = self.current_db.strip('`')
      self.cmdqueue.append(('use `%s`' % self.current_db) + ImpalaShell.CMD_DELIM)

  def _print_if_verbose(self, message):
    if self.verbose:
      print_to_stderr(message)

  def print_runtime_profile(self, profile, status=False):
    if self.show_profiles or status:
      if profile is not None:
        print "Query Runtime Profile:\n" + profile

  def _parse_table_name_arg(self, arg):
    """ Parses an argument string and returns the result as a db name, table name combo.

    If the table name was not fully qualified, the current database is returned as the db.
    Otherwise, the table is split into db/table name parts and returned.
    If an invalid format is provided, None is returned.
    """
    if not arg: return
    # If a multi-line argument, the name might be split across lines
    arg = arg.replace('\n', '')
    # Get the database and table name, using the current database if the table name
    # wasn't fully qualified.
    db_name, tbl_name = self.current_db, arg
    if db_name is None:
      db_name = ImpalaShell.DEFAULT_DB
    db_table_name = arg.split('.')
    if len(db_table_name) == 1:
      return db_name, db_table_name[0]
    if len(db_table_name) == 2:
      return db_table_name

  def do_alter(self, args):
    query = self.imp_client.create_beeswax_query("alter %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_create(self, args):
    query = self.imp_client.create_beeswax_query("create %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_drop(self, args):
    query = self.imp_client.create_beeswax_query("drop %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_load(self, args):
    query = self.imp_client.create_beeswax_query("load %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_profile(self, args):
    """Prints the runtime profile of the last INSERT or SELECT query executed."""
    if len(args) > 0:
      print_to_stderr("'profile' does not accept any arguments")
      return CmdStatus.ERROR
    elif self.last_query_handle is None:
      print_to_stderr('No previous query available to profile')
      return CmdStatus.ERROR
    profile = self.imp_client.get_runtime_profile(self.last_query_handle)
    return self.print_runtime_profile(profile, True)

  def do_select(self, args):
    """Executes a SELECT... query, fetching all rows"""
    query = self.imp_client.create_beeswax_query("select %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def _format_outputstream(self):
    column_names = self.imp_client.get_column_names(self.last_query_handle)
    if self.write_delimited:
      formatter = DelimitedOutputFormatter(field_delim=self.output_delimiter)
      self.output_stream = OutputStream(formatter, filename=self.output_file)
      # print the column names
      if self.print_header:
        self.output_stream.write([column_names])
    else:
      prettytable = self.construct_table_with_header(column_names)
      formatter = PrettyOutputFormatter(prettytable)
      self.output_stream = OutputStream(formatter, filename=self.output_file)

  def _periodic_wait_callback(self):
    """If enough time elapsed since the last call to the periodic callback,
    execute the RPC to get the query exec summary and depending on the set options
    print either the progress or the summary or both to stderr.
    """
    if not self.print_progress and not self.print_summary: return

    checkpoint = time.time()
    if checkpoint - self.last_summary > self.PROGRESS_UPDATE_INTERVAL:
      summary = self.imp_client.get_summary(self.last_query_handle)
      if summary and summary.progress:
        progress = summary.progress

        # If the data is not complete return and wait for a good result.
        if not progress.total_scan_ranges and not progress.num_completed_scan_ranges:
          self.last_summary = time.time()
          return

        data = ""
        if self.print_progress and progress.total_scan_ranges > 0:
          val = ((summary.progress.num_completed_scan_ranges * 100) /
                 summary.progress.total_scan_ranges)
          fragment_text = "[%s%s] %s%%\n" % ("#" * val, " " * (100 - val), val)
          data += fragment_text

        if self.print_summary:
          table = self._default_summary_table()
          output = []
          self.imp_client.build_summary_table(summary, 0, False, 0, False, output)
          formatter = PrettyOutputFormatter(table)
          data += formatter.format(output) + "\n"

        self.progress_stream.write(data)
      self.last_summary = time.time()

  def _default_summary_table(self):
    return self.construct_table_with_header(["Operator", "#Hosts", "Avg Time", "Max Time",
                                             "#Rows", "Est. #Rows", "Peak Mem",
                                             "Est. Peak Mem", "Detail"])

  def _execute_stmt(self, query, is_insert=False):
    """ The logic of executing any query statement

    The client executes the query and the query_handle is returned immediately,
    even as the client waits for the query to finish executing.

    If the query was not an insert, the results are fetched from the client
    as they are streamed in, through the use of a generator.

    The execution time is printed and the query is closed if it hasn't been already
    """
    try:
      self._print_if_verbose("Query: %s" % (query.query,))
      start_time = time.time()

      self.last_query_handle = self.imp_client.execute_query(query)
      self.query_handle_closed = False
      self.last_summary = time.time()
      wait_to_finish = self.imp_client.wait_to_finish(self.last_query_handle,
         self._periodic_wait_callback)
      # Reset the progress stream.
      self.progress_stream.clear()

      if is_insert:
        # retrieve the error log
        warning_log = self.imp_client.get_warning_log(self.last_query_handle)
        num_rows = self.imp_client.close_insert(self.last_query_handle)
      else:
        # impalad does not support the fetching of metadata for certain types of queries.
        if not self.imp_client.expect_result_metadata(query.query):
          # Close the query
          self.imp_client.close_query(self.last_query_handle)
          self.query_handle_closed = True
          return CmdStatus.SUCCESS

        self._format_outputstream()
        # fetch returns a generator
        rows_fetched = self.imp_client.fetch(self.last_query_handle)
        num_rows = 0

        for rows in rows_fetched:
          self.output_stream.write(rows)
          num_rows += len(rows)

        # retrieve the error log
        warning_log = self.imp_client.get_warning_log(self.last_query_handle)

      end_time = time.time()

      if warning_log:
        self._print_if_verbose(warning_log)
      # print insert when is_insert is true (which is 1)
      # print fetch when is_insert is false (which is 0)
      verb = ["Fetch", "Insert"][is_insert]
      self._print_if_verbose("%sed %d row(s) in %2.2fs" % (verb, num_rows,
                                                               end_time - start_time))

      if not is_insert:
        self.imp_client.close_query(self.last_query_handle, self.query_handle_closed)
      self.query_handle_closed = True

      profile = self.imp_client.get_runtime_profile(self.last_query_handle)
      self.print_runtime_profile(profile)
      return CmdStatus.SUCCESS
    except RPCException, e:
      # could not complete the rpc successfully
      # suppress error if reason is cancellation
      if self._no_cancellation_error(e):
        print_to_stderr(e)
    except QueryStateException, e:
      # an exception occurred while executing the query
      if self._no_cancellation_error(e):
        self.imp_client.close_query(self.last_query_handle, self.query_handle_closed)
        print_to_stderr(e)
    except DisconnectedException, e:
      # the client has lost the connection
      print_to_stderr(e)
      self.imp_client.connected = False
      self.prompt = ImpalaShell.DISCONNECTED_PROMPT
    except socket.error, (code, e):
      # if the socket was interrupted, reconnect the connection with the client
      if code == errno.EINTR:
        print ImpalaShell.CANCELLATION_MESSAGE
        self._reconnect_cancellation()
      else:
        print_to_stderr("Socket error %s: %s" % (code, e))
        self.prompt = self.DISCONNECTED_PROMPT
        self.imp_client.connected = False
    except Exception, u:
      # if the exception is unknown, there was possibly an issue with the connection
      # set the shell as disconnected
      print_to_stderr('Unknown Exception : %s' % (u,))
      self.imp_client.connected = False
      self.prompt = ImpalaShell.DISCONNECTED_PROMPT
    return CmdStatus.ERROR

  def _no_cancellation_error(self, error):
    if ImpalaShell.CANCELLATION_ERROR not in str(error):
      return True

  def construct_table_with_header(self, column_names):
    """ Constructs the table header for a given query handle.

    Should be called after the query has finished and before data is fetched.
    All data is left aligned.
    """
    table = ImpalaPrettyTable()
    for column in column_names:
      # Column names may be encoded as utf-8
      table.add_column(column.decode('utf-8', 'ignore'), [])
    table.align = "l"
    return table

  def do_values(self, args):
    """Executes a VALUES(...) query, fetching all rows"""
    query = self.imp_client.create_beeswax_query("values %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_with(self, args):
    """Executes a query with a WITH clause, fetching all rows"""
    query = self.imp_client.create_beeswax_query("with %s" % args,
                                                 self.set_query_options)
    # Set posix=True and add "'" to escaped quotes
    # to deal with escaped quotes in string literals
    lexer = shlex.shlex(query.query.lstrip(), posix=True)
    lexer.escapedquotes += "'"
    # Because the WITH clause may precede INSERT or SELECT queries,
    # just checking the first token is insufficient.
    is_insert = False
    tokens = list(lexer)
    if filter(self.INSERT_REGEX.match, tokens): is_insert = True
    return self._execute_stmt(query, is_insert=is_insert)

  def do_use(self, args):
    """Executes a USE... query"""
    query = self.imp_client.create_beeswax_query("use %s" % args,
                                                 self.set_query_options)
    if self._execute_stmt(query) is CmdStatus.SUCCESS:
      self.current_db = args
    else:
      return CmdStatus.ERROR

  def do_show(self, args):
    """Executes a SHOW... query, fetching all rows"""
    query = self.imp_client.create_beeswax_query("show %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_describe(self, args):
    """Executes a DESCRIBE... query, fetching all rows"""
    query = self.imp_client.create_beeswax_query("describe %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_desc(self, args):
    return self.do_describe(args)

  def do_insert(self, args):
    """Executes an INSERT query"""
    query = self.imp_client.create_beeswax_query("insert %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query, is_insert=True)

  def do_explain(self, args):
    """Explain the query execution plan"""
    query = self.imp_client.create_beeswax_query("explain %s" % args,
                                                 self.set_query_options)
    return self._execute_stmt(query)

  def do_history(self, args):
    """Display command history"""
    # Deal with readline peculiarity. When history does not exists,
    # readline returns 1 as the history length and stores 'None' at index 0.
    if self.readline and self.readline.get_current_history_length() > 0:
      for index in xrange(1, self.readline.get_current_history_length() + 1):
        cmd = self.readline.get_history_item(index)
        print_to_stderr('[%d]: %s' % (index, cmd))
    else:
      print_to_stderr("The readline module was either not found or disabled. Command "
                      "history will not be collected.")

  def do_tip(self, args):
    """Print a random tip"""
    print_to_stderr(random.choice(TIPS))

  def preloop(self):
    """Load the history file if it exists"""
    if self.readline:
      # The history file is created when the Impala shell is invoked and commands are
      # issued. In the first invocation of the shell, the history file will not exist.
      # Clearly, this is not an error, return.
      if not os.path.exists(self.history_file): return
      try:
        self.readline.read_history_file(self.history_file)
        self._replace_history_delimiters(ImpalaShell.HISTORY_FILE_QUERY_DELIM, '\n')
      except IOError, i:
        msg = "Unable to load command history (disabling history collection): %s" % i
        print_to_stderr(msg)
        # This history file exists but is not readable, disable readline.
        self._disable_readline()

  def postloop(self):
    """Save session commands in history."""
    if self.readline:
      try:
        self._replace_history_delimiters('\n', ImpalaShell.HISTORY_FILE_QUERY_DELIM)
        self.readline.write_history_file(self.history_file)
      except IOError, i:
        msg = "Unable to save command history (disabling history collection): %s" % i
        print_to_stderr(msg)
        # The history file is not writable, disable readline.
        self._disable_readline()

  def _replace_history_delimiters(self, src_delim, tgt_delim):
    """Replaces source_delim with target_delim for all items in history.

    Read all the items from history into a local list. Clear the history and copy them
    back after doing the transformation.
    """
    history_len = self.readline.get_current_history_length()
    # load the history and replace the shell's delimiter with EOL
    history_items = map(self.readline.get_history_item, xrange(1, history_len + 1))
    history_items = [item.replace(src_delim, tgt_delim) for item in history_items]
    # Clear the original history and replace it with the mutated history.
    self.readline.clear_history()
    for history_item in history_items:
      self.readline.add_history(history_item)

  def default(self, args):
    query = self.imp_client.create_beeswax_query(args, self.set_query_options)
    return self._execute_stmt(query)

  def emptyline(self):
    """If an empty line is entered, do nothing"""

  def do_version(self, args):
    """Prints the Impala build version"""
    print_to_stderr("Shell version: %s" % VERSION_STRING)
    print_to_stderr("Server version: %s" % self.server_version)

  def completenames(self, text, *ignored):
    """Make tab completion of commands case agnostic

    Override the superclass's completenames() method to support tab completion for
    upper case and mixed case commands.
    """
    cmd_names = [cmd for cmd in self.commands if cmd.startswith(text.lower())]
    # If the user input is upper case, return commands in upper case.
    if text.isupper(): return [cmd_names.upper() for cmd_names in cmd_names]
    # If the user input is lower case or mixed case, return lower case commands.
    return cmd_names

TIPS=[
  "Press TAB twice to see a list of available commands.",
  "After running a query, type SUMMARY to see a summary of where time was spent.",
  "The SET command shows the current value of all shell and query options.",
  "To see live updates on a query's progress, run 'set LIVE_SUMMARY=1;'.",
  "To see a summary of a query's progress that updates in real-time, run 'set \
LIVE_PROGRESS=1;'.",
  "The HISTORY command lists all shell commands in chronological order.",
  "The '-B' command line flag turns off pretty-printing for query results. Use this flag \
to remove formatting from results you want to save for later, or to benchmark Impala.",
  "You can run a single query from the command line using the '-q' option.",
  "When pretty-printing is disabled, you can use the '--output_delimiter' flag to set \
the delimiter for fields in the same row. The default is ','.",
  "Run the PROFILE command after a query has finished to see a comprehensive summary of \
all the performance and diagnostic information that Impala gathered for that query. Be \
warned, it can be very long!",
  "To see more tips, run the TIP command.",
  "Every command must be terminated by a ';'.",
  "Want to know what version of Impala you're connected to? Run the VERSION command to \
find out!",
  "You can change the Impala daemon that you're connected to by using the CONNECT \
command."
  "To see how Impala will plan to run your query without actually executing it, use the \
EXPLAIN command. You can change the level of detail in the EXPLAIN output by setting the \
EXPLAIN_LEVEL query option.",
  "When you set a query option it lasts for the duration of the Impala shell session."
  ]

HEADER_DIVIDER =\
  "***********************************************************************************"

def _format_tip(tip):
  """Takes a tip string and splits it on word boundaries so that it fits neatly inside the
  shell header."""
  return '\n'.join([l for l in textwrap.wrap(tip, len(HEADER_DIVIDER))])

WELCOME_STRING = """\
***********************************************************************************
Welcome to the Impala shell. Copyright (c) 2015 Cloudera, Inc. All rights reserved.
(%s)

%s
***********************************************************************************\
""" \
  % (VERSION_STRING, _format_tip(random.choice(TIPS)))

def print_to_stderr(message):
  print >> sys.stderr, message

def parse_query_text(query_text, utf8_encode_policy='strict'):
  """Parse query file text to extract queries and encode into utf-8"""
  return [q.encode('utf-8', utf8_encode_policy) for q in sqlparse.split(query_text)]

def execute_queries_non_interactive_mode(options):
  """Run queries in non-interactive mode."""
  queries = []
  if options.query_file:
    try:
      # "-" here signifies input from STDIN
      if options.query_file == "-":
        query_file_handle = sys.stdin
      else:
        query_file_handle = open(options.query_file, 'r')

      queries = parse_query_text(query_file_handle.read())
      if query_file_handle != sys.stdin:
        query_file_handle.close()
    except Exception, e:
      print_to_stderr('Error: %s' % e)
      sys.exit(1)
  elif options.query:
    queries = parse_query_text(options.query)
  shell = ImpalaShell(options)
  # The impalad was specified on the command line and the connection failed.
  # Return with an error, no need to process the query.
  if options.impalad and shell.imp_client.connected == False:
    sys.exit(1)
  queries = shell.cmdqueue + queries
  # Deal with case.
  sanitized_queries = []
  for query in queries:
    sanitized_queries.append(shell.sanitise_input(query))
  for query in sanitized_queries:
    # check if an error was encountered
    if shell.onecmd(query) is CmdStatus.ERROR:
      print_to_stderr('Could not execute command: %s' % query)
      if not options.ignore_query_failure:
        sys.exit(1)

if __name__ == "__main__":
  # pass defaults into option parser
  parser = get_option_parser(impala_shell_defaults)
  options, args = parser.parse_args()
  # use path to file specified by user in config_file option
  user_config = os.path.expanduser(options.config_file);
  # by default, use the .impalarc in the home directory
  config_to_load = impala_shell_defaults.get("config_file")
  # verify user_config, if found
  if os.path.isfile(user_config) and user_config != config_to_load:
    if options.verbose:
      print_to_stderr("Loading in options from config file: %s \n" % user_config)
    # Command line overrides loading ~/.impalarc
    config_to_load = user_config
  elif user_config != config_to_load:
    print_to_stderr('%s not found.\n' % user_config)
    sys.exit(1)

  # default options loaded in from impala_shell_config_defaults.py
  # options defaults overwritten by those in config file
  try:
    impala_shell_defaults.update(get_config_from_file(config_to_load))
  except Exception, e:
    msg = "Unable to read configuration file correctly. Check formatting: %s\n" % e
    print_to_stderr(msg)
    sys.exit(1)

  parser = get_option_parser(impala_shell_defaults)
  options, args = parser.parse_args()

  # Arguments that could not be parsed are stored in args. Print an error and exit.
  if len(args) > 0:
    print_to_stderr('Error, could not parse arguments "%s"' % (' ').join(args))
    parser.print_help()
    sys.exit(1)

  if options.version:
    print VERSION_STRING
    sys.exit(0)

  if options.use_kerberos and options.use_ldap:
    print_to_stderr("Please specify at most one authentication mechanism (-k or -l)")
    sys.exit(1)

  if not options.ssl and not options.creds_ok_in_clear and options.use_ldap:
    print_to_stderr("LDAP credentials may not be sent over insecure " +
                    "connections. Enable SSL or set --auth_creds_ok_in_clear")
    sys.exit(1)

  if options.use_kerberos:
    print_to_stderr("Starting Impala Shell using Kerberos authentication")
    print_to_stderr("Using service name '%s'" % options.kerberos_service_name)
    # Check if the user has a ticket in the credentials cache
    try:
      if call(['klist', '-s']) != 0:
        print_to_stderr(("-k requires a valid kerberos ticket but no valid kerberos "
                         "ticket found."))
        sys.exit(1)
    except OSError, e:
      print_to_stderr('klist not found on the system, install kerberos clients')
      sys.exit(1)
  elif options.use_ldap:
    print_to_stderr("Starting Impala Shell using LDAP-based authentication")
  else:
    print_to_stderr("Starting Impala Shell without Kerberos authentication")

  if options.ssl:
    if options.ca_cert is None:
      print_to_stderr("SSL is enabled. Impala server certificates will NOT be verified"\
                      " (set --ca_cert to change)")
    else:
      print_to_stderr("SSL is enabled")

  if options.output_file:
    try:
      # Make sure the given file can be opened for writing. This will also clear the file
      # if successful.
      open(options.output_file, 'wb')
    except IOError, e:
      print_to_stderr('Error opening output file for writing: %s' % e)
      sys.exit(1)

  if options.query or options.query_file:
    if options.print_progress or options.print_summary:
      print_to_stderr("Error: Live reporting is available for interactive mode only.")
      sys.exit(1)

    execute_queries_non_interactive_mode(options)
    sys.exit(0)

  intro = WELCOME_STRING
  if not options.ssl and options.creds_ok_in_clear and options.use_ldap:
    intro += ("\n\\nLDAP authentication is enabled, but the connection to Impala is " +
              "not secured by TLS.\nALL PASSWORDS WILL BE SENT IN THE CLEAR TO IMPALA.\n")

  shell = ImpalaShell(options)
  while shell.is_alive:
    try:
      try:
        shell.cmdloop(intro)
      except KeyboardInterrupt:
        intro = '\n'
      # A last measure against any exceptions thrown by an rpc
      # not caught in the shell
      except socket.error, (code, e):
        # if the socket was interrupted, reconnect the connection with the client
        if code == errno.EINTR:
          print shell.CANCELLATION_MESSAGE
          shell._reconnect_cancellation()
        else:
          print_to_stderr("Socket error %s: %s" % (code, e))
          shell.imp_client.connected = False
          shell.prompt = shell.DISCONNECTED_PROMPT
      except DisconnectedException, e:
        # the client has lost the connection
        print_to_stderr(e)
        shell.imp_client.connected = False
        shell.prompt = shell.DISCONNECTED_PROMPT
      except QueryStateException, e:
        # an exception occurred while executing the query
        if shell._no_cancellation_error(e):
          shell.imp_client.close_query(shell.last_query_handle,
                                       shell.query_handle_closed)
          print_to_stderr(e)
      except RPCException, e:
        # could not complete the rpc successfully
        # suppress error if reason is cancellation
        if shell._no_cancellation_error(e):
          print_to_stderr(e)
    finally:
      intro = ''
