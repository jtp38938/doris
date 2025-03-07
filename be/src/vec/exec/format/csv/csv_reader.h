// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <gen_cpp/PlanNodes_types.h>
#include <stddef.h>
#include <stdint.h>

#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "common/status.h"
#include "io/file_factory.h"
#include "io/fs/file_reader_writer_fwd.h"
#include "util/slice.h"
#include "vec/data_types/data_type.h"
#include "vec/exec/format/generic_reader.h"

namespace doris {

class LineReader;
class TextConverter;
class Decompressor;
class SlotDescriptor;
class RuntimeProfile;
class RuntimeState;

namespace io {
class FileSystem;
class IOContext;
} // namespace io
struct TypeDescriptor;

namespace vectorized {

struct ScannerCounter;
class Block;

class CsvReader : public GenericReader {
public:
    CsvReader(RuntimeState* state, RuntimeProfile* profile, ScannerCounter* counter,
              const TFileScanRangeParams& params, const TFileRangeDesc& range,
              const std::vector<SlotDescriptor*>& file_slot_descs, io::IOContext* io_ctx);

    CsvReader(RuntimeProfile* profile, const TFileScanRangeParams& params,
              const TFileRangeDesc& range, const std::vector<SlotDescriptor*>& file_slot_descs,
              io::IOContext* io_ctx);
    ~CsvReader() override;

    Status init_reader(bool is_query);
    Status get_next_block(Block* block, size_t* read_rows, bool* eof) override;
    Status get_columns(std::unordered_map<std::string, TypeDescriptor>* name_to_type,
                       std::unordered_set<std::string>* missing_cols) override;

    // get schema of csv file from first one line or first two lines.
    // if file format is FORMAT_CSV_DEFLATE and if
    // 1. header_type is empty, get schema from first line.
    // 2. header_type is CSV_WITH_NAMES, get schema from first line.
    // 3. header_type is CSV_WITH_NAMES_AND_TYPES, get schema from first two line.
    Status get_parsed_schema(std::vector<std::string>* col_names,
                             std::vector<TypeDescriptor>* col_types) override;

private:
    // used for stream/broker load of csv file.
    Status _create_decompressor();
    Status _fill_dest_columns(const Slice& line, Block* block,
                              std::vector<MutableColumnPtr>& columns, size_t* rows);
    Status _line_split_to_values(const Slice& line, bool* success);
    void _split_line(const Slice& line);
    void _split_line_for_single_char_delimiter(const Slice& line);
    void _split_line_for_proto_format(const Slice& line);
    Status _check_array_format(std::vector<Slice>& split_values, bool* is_success);
    bool _is_null(const Slice& slice);
    bool _is_array(const Slice& slice);
    void _init_system_properties();
    void _init_file_description();

    // used for parse table schema of csv file.
    // Currently, this feature is for table valued function.
    Status _prepare_parse(size_t* read_line, bool* is_parse_name);
    Status _parse_col_nums(size_t* col_nums);
    Status _parse_col_names(std::vector<std::string>* col_names);
    // TODO(ftw): parse type
    Status _parse_col_types(size_t col_nums, std::vector<TypeDescriptor>* col_types);

    RuntimeState* _state;
    RuntimeProfile* _profile;
    ScannerCounter* _counter;
    const TFileScanRangeParams& _params;
    const TFileRangeDesc& _range;
    FileSystemProperties _system_properties;
    FileDescription _file_description;
    const std::vector<SlotDescriptor*>& _file_slot_descs;
    // Only for query task, save the file slot to columns in block map.
    // eg, there are 3 cols in "_file_slot_descs" named: k1, k2, k3
    // and this 3 columns in block are k2, k3, k1,
    // the _file_slot_idx_map will save: 2, 0, 1
    std::vector<int> _file_slot_idx_map;
    // Only for query task, save the columns' index which need to be read.
    // eg, there are 3 cols in "_file_slot_descs" named: k1, k2, k3
    // and the corresponding position in file is 0, 3, 5.
    // So the _col_idx will be: <0, 3, 5>
    std::vector<int> _col_idxs;
    // True if this is a load task
    bool _is_load = false;

    std::shared_ptr<io::FileSystem> _file_system;
    io::FileReaderSPtr _file_reader;
    std::unique_ptr<LineReader> _line_reader;
    bool _line_reader_eof;
    std::unique_ptr<TextConverter> _text_converter;
    std::unique_ptr<Decompressor> _decompressor;

    TFileFormatType::type _file_format_type;
    bool _is_proto_format;
    TFileCompressType::type _file_compress_type;
    int64_t _size;
    // When we fetch range start from 0, header_type="csv_with_names" skip first line
    // When we fetch range start from 0, header_type="csv_with_names_and_types" skip first two line
    // When we fetch range doesn't start from 0 will always skip the first line
    int _skip_lines;

    std::string _value_separator;
    std::string _line_delimiter;
    int _value_separator_length;
    int _line_delimiter_length;
    bool _trim_double_quotes = false;

    io::IOContext* _io_ctx;

    // save source text which have been splitted.
    std::vector<Slice> _split_values;
};
} // namespace vectorized
} // namespace doris
