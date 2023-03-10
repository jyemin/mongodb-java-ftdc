#!/usr/bin/env ruby
#
require 'bundler/inline'
require 'json'
require 'csv'

gemfile do
  source 'https://rubygems.org'

  gem 'pry-byebug'
  gem 'progress_bar'
end

def flatten_json(json, depth = -1)
  return {} if depth == 0

  if json.is_a?(Hash)
    flat = {}
    json.each do |key, value|
      flat_assign(flat, key, value, depth)
    end
    flat

  elsif json.is_a?(Array)
    flat = {}
    json.each_with_index do |value, i|
      flat_assign(flat, i, value, depth)
    end
    flat

  else # number or string or nil
    json
  end
end

def flat_assign(dest, key, value, depth)
  flat_value = flatten_json(value, depth - 1)
  if flat_value.is_a?(Hash)
    flat_value.each do |k,v|
      dest["#{key}.#{k}"] = v
    end
  else
    dest["#{key}"] = flat_value
  end
  dest
end

EXCLUDE_KEYS = /(clientId|topology.type|connectionPools.*.address|topology.*.address|topology.*.state|topology.*.type)/
CALCULATE_DELTA = /(connectionPools.*|commands.serverErrorResponse.*|commands.gte*|commands.*Timeout|commands.completed)/

def calculate_value_delta(field, doc, prevDoc)
  # set a default of 0 for any missing fields to ensure CSV aligns
  value = doc[field] || 0
  if prevDoc && CALCULATE_DELTA === field
    lastValue = (prevDoc[field] || 0)
    # add a test here to ensure the values don't swing into the negative
    value -= lastValue if (value - lastValue >= 0)
  end
  value
end

if ARGV.length < 1
  puts "FTDC filename expected (ex: metrics.2023-03-07T21-09-32.707759Z)"
  exit(1)
end

filename = ARGV.first
lines = `wc -l < #{filename}`.to_i
pb = ProgressBar.new(lines)
puts "Generating CSV from #{filename}"

fields = nil

# first identify all unique
puts "Gathering Headers ..."
File.readlines(filename).each do |line|
  pb.increment!
  doc = flatten_json(JSON.parse(line))
  # skip any non-telemetry document
  next if doc["type"] != 2
  # FIXME
  # String out field paths that contain non-numeric values
  doc.delete_if { |k, _| EXCLUDE_KEYS === k }
  fields = doc.keys if fields.nil?

  # append any missing fields to the field list
  fields += doc.keys - fields
end

lastDoc = nil
outfile = (filename.split('.')[0..1] << "csv").join('.')
pb = ProgressBar.new(lines)

puts "Writing CSV file ..."
# next write csv using all identified fields
CSV.open(outfile, "w") do |csv|
  csv << fields
  File.readlines(filename).each do |line|
    pb.increment!
    doc = flatten_json(JSON.parse(line))
    next if doc["type"] != 2
    # iterate over the known field list and flush values in order
    csv << fields.map { |k| calculate_value_delta(k, doc, lastDoc) }
    lastDoc = doc
  end
end