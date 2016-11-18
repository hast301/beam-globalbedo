import glob
import os
import calendar
import datetime
from pmonitor import PMonitor


################################################################################
# Reprojects Meteosat MVIRI(/SEVIRI) 'orbit'(disk) products onto MODIS SIN tiles
#
__author__ = 'olafd'
#
################################################################################

#years = ['1985']    #test  
#years = ['2007','2008']    #test  
#years = ['1989','1990','1991','1992','1993','1994','1995','1996','1997']  
#years = ['1998','1999','2000','2001']     
#years = ['2002','2003','2004','2005']     
years = ['2006','2005','2004']

#allMonths = ['01', '02', '03']
allMonths = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12']

diskIds = ['000', '057', '063'] 
#diskIds = ['000', '063'] 
#diskIds = ['057', '063'] 
#diskIds = ['000'] 
#diskIds = ['063'] 
#diskIds = ['057'] 
hIndices = ['09', '10', '11', '12', '13', '14', '15', '16', '17', '18', '19', '20', '21', '22', '23', '24', '25', '26', '27', '28', '29', '30', '31', '32']


######################## BRF orbits --> tiles: ###########################

gaRootDir = '/group_workspaces/cems2/qa4ecv/vol4/olafd/GlobAlbedoTest'
beamDir = '/group_workspaces/cems2/qa4ecv/vol4/software/beam-5.0.1'

inputs = ['dummy']
m = PMonitor(inputs, 
             request='ga-l2-meteosat-brf-tiles',
             logdir='log', 
             hosts=[('localhost',192)],
             types=[('ga-l2-meteosat-brf-tiles-step.sh',192)])

for diskId in diskIds:

    if diskId == '000':
        diskIdString = 'MVIRI_C_BRF'
    elif diskId == '057':
        diskIdString = 'MVIRI_057_C_BRF'
    else:
        diskIdString = 'MVIRI_063_C_BRF'

    for year in years:
        brfTileDir = gaRootDir + '/BRF/MVIRI/' + year 
        brfOrbitDir = gaRootDir + '/BRF_orbits/MVIRI/' + year 
        if os.path.exists(brfOrbitDir):
            brfFiles = os.listdir(brfOrbitDir)
            if len(brfFiles) > 0:
                for index in range(0, len(brfFiles)):
                    if diskIdString in brfFiles[index]:
	                brfOrbitFilePath = brfOrbitDir + '/' + brfFiles[index]
                        #print 'index, brfOrbitFilePath', index, ', ', brfOrbitFilePath
                        for hIndex in hIndices:
                            m.execute('ga-l2-meteosat-brf-tiles-step.sh', ['dummy'], 
                                                                           [brfTileDir], 
                                                                           parameters=[brfOrbitFilePath,brfFiles[index],brfTileDir,diskId,hIndex,gaRootDir,beamDir])

m.wait_for_completion()
