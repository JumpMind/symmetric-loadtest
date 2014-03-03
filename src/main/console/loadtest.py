from org.jumpmind.symmetric.loadtest import SymmetricProtocolHelper
from java.lang import String
from java.lang import Integer
from java.lang import Long
from net.grinder.script import Test
from net.grinder.script.Grinder import grinder
from net.grinder.plugin.http import HTTPPluginControl, HTTPRequest
from HTTPClient import NVPair
connectionDefaults = HTTPPluginControl.getConnectionDefaults()
httpUtilities = HTTPPluginControl.getHTTPUtilities()

# These definitions at the top level of the file are evaluated once,
# when the worker process is started.

connectionDefaults.defaultHeaders = \
  [ NVPair('Accept', 'text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2'),
    NVPair('User-Agent', 'Java/1.6.0_26'), ]

headers= \
  [ NVPair('Cache-Control', 'no-cache'), ]

log = grinder.logger  

url = grinder.properties.get('server.url')

log.info('The server url to use is %s' % url)

serverPath = grinder.properties.get('server.path');

log.info('The server path is %s' % serverPath)

helper = SymmetricProtocolHelper(grinder)      

log.info('The variable replacer was created')

httpRequest = HTTPRequest(url=url, headers=headers)

log.info('The HTTP request object was created')     

def pull():
    token_nodeId = helper.nodeId
    token_securityToken = grinder.properties.getProperty('server.auth.token', 'test')      

    result = httpRequest.GET(serverPath + '/pull' + '?nodeId=' + token_nodeId +
      '&securityToken=' + token_securityToken)
      
    if result.statusCode == 200:
        ackData = helper.generateAck(String(result.data))
        result = httpRequest.POST(serverPath + '/ack' + '?nodeId=' + token_nodeId +
                                '&securityToken=' + token_securityToken, ackData)
    else:
        grinder.statistics.forCurrentTest.success = 0 
    

def push():      
    token_nodeId = helper.nodeId
    token_securityToken = grinder.properties.getProperty('server.auth.token', 'test')      

    result = httpRequest.HEAD(serverPath + '/push' +
      '?nodeId=' +
      token_nodeId +
      '&securityToken=' +
      token_securityToken)
      
    if result.statusCode == 200:
        result = httpRequest.PUT(serverPath + '/push' + '?nodeId=' + token_nodeId +
                                '&securityToken=' + token_securityToken,
         helper.generateBatchPayload())
        
        resultString = String(String(result.data).toUpperCase())
        
        if result.statusCode != 200 or resultString.contains('=ER') or resultString.contains('=SK'):
            log.warn("Failed to push.  The status code was %d and the response string was %s" % (result.statusCode, resultString))
            grinder.statistics.forCurrentTest.success = 0        
    else:
        grinder.statistics.forCurrentTest.success = 0 

    return result

pullTest = Test(1, 'PULL').wrap(pull)
pushTest = Test(2, 'PUSH').wrap(push)

log.info('The Test objects were created')

class TestRunner:  

  def __call__(self):

    grinder.logger.info('Checking grinder.agents to see if test needs to run')
      
    locationCount = len(helper.getLocationIds())
    if  locationCount > 0:      
        grinder.logger.info("Found %d locations for key %s" % (locationCount, helper.getLocationPropertyKey()))
        maxAgents = grinder.properties.getInt('grinder.agents', 100)
        if maxAgents <= grinder.agentNumber:   
            grinder.logger.info('Not running agent %d because the max number of agents was %d' % (grinder.agentNumber, maxAgents))      
            return
        	
        grinder.logger.info('Running agent')
        
        pullTest() 
        pushTest()            
        
        grinder.sleep(Long.parseLong(grinder.properties.get('time.between.sync.ms')))
        
    else:
        grinder.logger.info('No location assigned to this agent: %s' % helper.getLocationPropertyKey())
    


