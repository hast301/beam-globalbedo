#!/bin/bash

### get input parameters
sensorID=$1
tile=$2
year=$3
doy=$4
gaRootDir=$5
spectralSdrRootDir=$6
spectralInversionRootDir=$7
beamRootDir=${8}
spectralAlbedoTargetDir=${9}  # remind the brackets if argument index >= 10!!

bandIndex=3  # we do band 3for the moment

### set GPT
gpt=$beamRootDir/bin/gpt-d-l2.sh

spectralInversionNosnowTargetDir=$spectralInversionRootDir/NoSnow/$year/$tile
spectralInversionSnowTargetDir=$spectralInversionRootDir/Snow/$year/$tile
spectralInversionMergeTargetDir=$spectralInversionRootDir/Merge/$year/$tile
if [ ! -d "$spectralInversionNosnowTargetDir" ]
then
   mkdir -p $spectralInversionNosnowTargetDir
fi
if [ ! -d "$spectralInversionSnowTargetDir" ]
then
   mkdir -p $spectralInversionSnowTargetDir
fi
if [ ! -d "$spectralInversionMergeTargetDir" ]
then
   mkdir -p $spectralInversionMergeTargetDir
fi

if [ ! -d "$spectralAlbedoTargetDir" ]
then
   mkdir -p $spectralAlbedoTargetDir
fi

echo "BRDF computation for tile: '$tile' , year $year, DoY $doy ..."

echo "Compute NOSNOW SPECTRAL BRDF for tile $tile, year $year, DoY $doy, ..."
TARGET=${spectralInversionNosnowTargetDir}/Qa4ecv.brdf.spectral.$year$doy.$tile.NoSnow.nc
echo "time $gpt ga.l3.inversion.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PcomputeSnow=false -PnumSdrBands=1 -PsingleBandIndex=$bandIndex -PsubtileFactor=1 -PsdrRootDir=$spectralSdrRootDir -e -f NetCDF4-BEAM -t $TARGET"
time $gpt ga.l3.inversion.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PcomputeSnow=false -PnumSdrBands=1 -PsingleBandIndex=$bandIndex -PsubtileFactor=1 -PsdrRootDir=$spectralSdrRootDir -e -f NetCDF4-BEAM -t $TARGET
status=$?

echo "Status: $status"

if [ "$status" -eq 0 ]; then
    echo "Compute SNOW SPECTRAL BRDF for tile $tile, year $year, DoY $doy, ..."
    TARGET=${spectralInversionSnowTargetDir}/Qa4ecv.brdf.spectral.$year$doy.$tile.Snow.nc
    echo "time $gpt ga.l3.inversion.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PcomputeSnow=true -PnumSdrBands=1 -PsingleBandIndex=$bandIndex -PsubtileFactor=1 -PsdrRootDir=$spectralSdrRootDir -e -f NetCDF4-BEAM -t $TARGET"
    time $gpt ga.l3.inversion.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PcomputeSnow=true -PnumSdrBands=1 -PsingleBandIndex=$bandIndex -PsubtileFactor=1 -PsdrRootDir=$spectralSdrRootDir -e -f NetCDF4-BEAM -t $TARGET
    status=$?
    echo "Status: $status"
fi

# later if needed (maybe BRDF is enough? See email F.Boersma 20170524)
:
#if [ "$status" -eq 0 ]; then
#    echo "Compute MERGED SPECTRAL BRDF for tile $tile, year $year, DoY $doy ..."
#    TARGET=${spectralInversionMergeTargetDir}/Qa4ecv.brdf.spectral.$year$doy.$tile.Merge.nc
#    echo "time $gpt ga.l3.albedo.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PinversionRootDir=$spectralInversionRootDir -PmergedProductOnly=true -e -f NetCDF4-BEAM -t $TARGET"
#    time $gpt ga.l3.albedo.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PinversionRootDir=$spectralInversionRootDir -PmergedProductOnly=true -e -f NetCDF4-BEAM -t $TARGET
#    status=$?
#    echo "Status: $status"
#fi

# later:
#if [ "$status" -eq 0 ]; then
#    echo "Compute ALBEDO for tile $tile, year $year, DoY $doy ..."
#    TARGET=$spectralAlbedoTargetDir/Qa4ecv.albedo.spectral.$sensorID.$year$doy.$tile.nc
#    echo "time $gpt ga.l3.albedo.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PinversionRootDir=$spectralInversionRootDir -e -f NetCDF4-BEAM -t $TARGET"
#    time $gpt ga.l3.albedo.spectral -Ptile=$tile -Pyear=$year -Pdoy=$doy -PinversionRootDir=$spectralInversionRootDir -e -f NetCDF4-BEAM -t $TARGET
#    status=$?
#    echo "Status: $status"
#fi

echo `date`
