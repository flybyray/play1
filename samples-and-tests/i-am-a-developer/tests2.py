#!/usr/bin/python
# -*- coding: utf-8 -*

import unittest
import os
import shutil
import sys
import subprocess
import re
import time
import urllib2
import mechanize
import threading

# --- TESTS

class IamADeveloper(unittest.TestCase):
    
    def testDependenciesManager(self):

        # Testing job developing
        step('Hello, I am testing #2073')

        self.working_directory = bootstrapWorkingDirectory('i-am-testing-2073')
    
        step('Create a new project')
    
        self.play = callPlay(self, ['new', '%s/app2073' % self.working_directory, '--name=TEST2073'])
        self.assert_(waitFor(self.play, 'The new application will be created'))
        self.assert_(waitFor(self.play, 'OK, the application is created'))
        self.assert_(waitFor(self.play, 'Have fun!'))
        
        self.play.wait()
    
        step('Create a new module')

        self.play = callPlay(self, ['new-module', '%s/mod2073' % self.working_directory])
        self.assert_(waitFor(self.play, 'The new module will be created'))
        self.assert_(waitFor(self.play, 'OK, the module is created'))
        self.assert_(waitFor(self.play, 'Have fun!'))
        
        self.play.wait()

        step('Configure app and module')

        edit('%s/mod2073' % self.working_directory, "conf/dependencies.yml", 1, 'self: play.test.modules -> mod2073 0.1')
    
        app = '%s/app2073' % self.working_directory
            
        #inserting module dependencies
        insert(app, "conf/dependencies.yml", 5, '    - play.test.modules -> mod2073 latest.integration')
        insert(app, "conf/dependencies.yml", 6, '')
        insert(app, "conf/dependencies.yml", 7, 'repositories:')
        insert(app, "conf/dependencies.yml", 8, '    - playLocalTestModulesRep:')
        insert(app, "conf/dependencies.yml", 9, '        type: local')
        insert(app, "conf/dependencies.yml", 10, '        descriptor: ${application.path}/../[module]/conf/dependencies.yml')
        insert(app, "conf/dependencies.yml", 11, '        artifact: ${application.path}/../[module]')
        insert(app, "conf/dependencies.yml", 12, '        contains:')
        insert(app, "conf/dependencies.yml", 13, '            - play.test.modules -> *')

        step('Create a special file in module')
        
        createDir('%s/mod2073' % self.working_directory, 'public')
        createDir('%s/mod2073' % self.working_directory, 'public/Unterlagen BBank-Reporting')
        create('%s/mod2073' % self.working_directory, 'public/Unterlagen BBank-Reporting/Anhang Performance-Bericht_ Übersicht §18 Bundesbank gesetz Meldepflichten.PDF')

        # Run dependencies on the newly created application
        step('Run dependencies on our #2073-application')
    
        self.play = callPlay(self, ['dependencies', app, '--forProd'])

        self.assert_(waitFor(self.play, 'Installing resolved dependencies'))
        self.assert_(waitFor(self.play, 'Done!'))

        step("done testing #2073")



# --- UTILS

def bootstrapWorkingDirectory( folder ):
    test_base = os.path.normpath(os.path.dirname(os.path.realpath(sys.argv[0])))
    working_directory = os.path.join(test_base, folder )
    if(os.path.exists(working_directory)):
        shutil.rmtree(working_directory)
    os.mkdir(working_directory)
    return working_directory

def callPlay(self, args):
    play_script = os.path.join(self.working_directory, '../../../play')
    process_args = [play_script] + args
    play_process = subprocess.Popen(process_args,stdout=subprocess.PIPE)
    return play_process

#returns true when pattern is seen
def waitFor(process, pattern):
    return waitForWithFail(process, pattern, "")
    

#returns true when pattern is seen, but false if failPattern is not seen or if timeout
def waitForWithFail(process, pattern, failPattern):
    timer = threading.Timer(90, timeout, [process])
    timer.start()
    while True:
        sys.stdout.flush()
        line = process.stdout.readline().strip()
	sys.stdout.flush()
        #print timeoutOccurred
        if timeoutOccurred:
            return False
        if line == '@KILLED':
            return False
        if line: print line
        if failPattern != "" and line.count(failPattern):
            timer.cancel()
            return False
        if line.count(pattern):
            timer.cancel()
            return True

timeoutOccurred = False

def timeout(process):
    global timeoutOccurred 
    print '@@@@ TIMEOUT !'
    killPlay()
    timeoutOccurred = True

def killPlay():
    try:
        urllib2.urlopen('http://localhost:9000/@kill')
    except:
        pass

def step(msg):
    print
    print '# --- %s' % msg
    print

def edit(app, file, line, text):
    fname = os.path.join(app, file)
    source = open(fname, 'r')
    lines = source.readlines()
    lines[line-1] = '%s\n' % text
    source.close()
    source = open(fname, 'w')
    source.write(''.join(lines))
    source.close()
    os.utime(fname, None)

def insert(app, file, line, text):
    fname = os.path.join(app, file)
    source = open(fname, 'r')
    lines = source.readlines()
    lines[line-1:line-1] = '%s\n' % text
    source.close()
    source = open(fname, 'w')
    source.write(''.join(lines))
    source.close()
    os.utime(fname, None)

def create(app, file):
    fname = os.path.join(app, file)
    source = open(fname, 'w')
    source.close()
    os.utime(fname, None)

def createDir(app, file):
    fname = os.path.join(app, file)
    os.mkdir( fname )


def delete(app, file, line):
    fname = os.path.join(app, file)
    source = open(fname, 'r')
    lines = source.readlines()
    del lines[line-1]
    source.close()
    source = open(fname, 'w')
    source.write(''.join(lines))
    source.close()
    os.utime(fname, None)    

def rename(app, fro, to):
    os.rename(os.path.join(app, fro), os.path.join(app, to))

if __name__ == '__main__':
    unittest.main()
